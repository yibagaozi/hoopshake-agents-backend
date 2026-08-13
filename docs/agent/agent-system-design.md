# HOOPSHAKE 多 Agent 系统详细设计

`v1.0 · 2026-08-13 · 云端 /api/student · /api/teacher`

本篇补全技术参考手册 §4/§5/§6 留下的空白:把"组件总览 + 设计哲学"落成**可实现的契约与装配顺序**——Harness 运行时、Agent 路由、RAG 召回、Skill、Tools 调用、日志审计的 boot。配套的对外 HTTP/SSE 端点见 `cloud-agent-api.md`;学生端产品流程见 `../product/student-agent-ux.md`。

> 阅读前提:先看 `../design-audit.md` §E 的落地顺序。本篇假设地基(词表权威化、audit_log 统一、GLM 单供应商)已按审计结论收敛。凡与现有代码冲突处,本篇以"目标态"描述并标注差异。

---

## 0. 设计立场(三条不变量)

整套设计围绕三条不变量展开,后面每个决定都能回溯到这里:

1. **框架可换,业务不动。** 业务层只认 `AgentRuntime`,永不 import SAA / Spring AI 类型。编排框架(SAA 的 supervisor/handoff,或退化为 Spring AI 原生 `ChatClient`)是 `adapter` 包里的一个实现细节,ArchUnit 强制。理由:SAA 还在 M 版(技术 §2.1),不能让预览版 API 渗进业务代码。
2. **能用规则不用 Workflow,能用 Workflow 不用 Agent。** (技术 §3.3)Director 阶段一是规则路由不是 LLM;即时反馈是边缘规则引擎不经 LLM;只有"学生问题千变万化"这类真的需要按中间结果调整的场景才上 ReAct Agent。
3. **学生只能看到自己的数据,这条在三层各设一道闸。** web 鉴权(第一道)、Harness Hook 注入 studentId(第二道)、audit_log 事后可查(第三道)。任何一层被绕过,另两层兜底。

---

## 1. 分层与包结构

技术 §9.1 定的分层是 `web → application → harness`,domain 被各层依赖。本篇把 harness 内部展开:

```
cloud/
  web/            REST/SSE 控制器、鉴权、参数校验          ── 不 import harness.adapter
  application/    编排、事务、studentId 注入、Mode 决议    ── 调 AgentRuntime,不碰 SAA
  harness/
    AgentRuntime           业务侧唯一入口(execute/stream/interrupt)
    spec/                  AgentSpec / ToolSpec / HookSpec(声明式,纯 POJO/record)
    permission/            PermissionMode + 仲裁器
    hook/                  Pre/PostToolUseHook 链
    session/               AgentSession(Working memory 句柄)+ 事件记录
    llm/                   LlmGateway(档位/预算/缓存/脱敏/降级)
    tool/                  ToolRegistry + 各业务工具实现(读结论层)
    rag/                   RagRetriever(Modular RAG 编排)
    memory/                MemoryStore(Episodic/Semantic 读写)
    adapter/
      saa/                 ★唯一允许 import SAA / Spring AI 编排类型的包(ArchUnit)
  domain/         实体、枚举、词表加载
```

**为什么 `adapter/saa` 单独一包并用 ArchUnit 锁**:一旦 SAA 类型散落进 tool/rag/application,换框架(或 SAA M 版 breaking change)就要全仓改。锁在一个包里,换框架 = 换一个包的实现。ArchUnit 规则(测试期):`no classes outside ..harness.adapter.. should depend on com.alibaba.cloud.ai.. or org.springframework.ai.chat.client..`。

---

## 2. 组件清单(对齐技术 §4.1,标注阶段一交付边界)

| 组件 | 类型 | 执行模式 | Reflection | 阶段一 | Harness 落点 |
|---|---|---|---|---|---|
| **Coach Director** | Supervisor | 规则路由(降级自 ReAct iter=1) | ❌ | ✅ | `application` 层的 `RouterService` |
| **Skill Coach** | ReAct Agent | ReAct(iter≤5) | ⚠️ 选择性 | ✅ 主力 | `AgentSpec("skill_coach")` |
| **Curriculum** | ReAct Agent | Plan-and-Execute | ✅ Evaluator | 二期 | `AgentSpec("curriculum")` + async_tasks |
| **Safety Detection** | Workflow | 规则+LLM | ❌ | ✅(云端语义判定部分) | `Workflow`,非 Agent |
| Perception | Workflow | 固定流程 | ❌ | 算法侧 | 不在云端 Harness |
| Commentary | 单次 LLM | — | ❌ | 后期 | `LlmGateway.oneShot()` |
| Family Coach | ReAct Agent | 双层 Reflection(异构) | ✅ 强制 | 【预留】501 | `AgentSpec("family_coach")` 禁用态 |
| AI Ops | ReAct Agent | ReAct | ❌ | 后期 | — |

**阶段一实际跑起来的只有三个东西**:规则路由(Director)、Skill Coach(学生主力)、Safety Detection 的云端语义部分。其余是预留位。这与技术 §3.3 的"5 Agent + 3 Workflow + 1 单次 LLM"是**目标态**,不是阶段一清单——文档必须把这条说透,否则会被理解成一期要交八个 Agent。

---

## 3. Harness 运行时:核心抽象

### 3.1 AgentRuntime —— 业务侧唯一入口

```java
public interface AgentRuntime {
    /** 同步:一次拿完整回答(教师简单查询、内部调用) */
    AgentResult execute(AgentInvocation invocation);
    /** 流式:逐字/逐事件输出(学生对话主路) */
    void stream(AgentInvocation invocation, AgentEventSink sink);
    /** 中断:Plan-and-Execute 的"教师改 Plan"、学生点停止 */
    void interrupt(String runId);
}
```

`AgentInvocation` 是一次调用的完整输入,**由 application 层组装**,harness 不碰 web:

```java
record AgentInvocation(
    String agentType,          // "skill_coach" / "curriculum" ...(路由结果)
    PermissionContext perm,    // Mode + 已用预算 + 数据主体 studentId(见 §5)
    UUID chatSessionId,        // Working memory 键
    String userText,
    Map<String,Object> anchors // 可选:trainingSessionId / lessonId / clipId
) {}
```

`AgentEventSink` 是流式事件的下游,web 把它接到 SSE。事件类型与 `ChatDtos` 已定义的 SSE 负载一一对应:

| Sink 事件 | 对应 `ChatDtos` | 时机 |
|---|---|---|
| `onMeta(messageId, sessionId)` | `ChatMetaEvent` | 落一条 ASSISTANT 空壳后立即 |
| `onToolCall(name,status,label)` | `ChatToolEvent` | 每次工具进入/完成 |
| `onDelta(text)` | `ChatDeltaEvent` | LLM 逐 token |
| `onDone(finishReason,tokens,suggestions)` | `ChatDoneEvent` | 收尾 |

**为什么 sink 而不是返回 `Flux`**:业务层不该被 Reactor 类型绑架(和"不 import 框架类型"同源)。sink 是一个纯回调接口,底层用 SSE、WebFlux 还是虚拟线程 + 阻塞写,都是实现细节。

### 3.2 AgentSpec / ToolSpec / HookSpec —— 声明式定义

Agent 不是一堆散落的 if-else,是一份**声明**,启动时注册进 `AgentRegistry`:

```java
record AgentSpec(
    String type,                      // "skill_coach"
    ExecutionMode mode,               // REACT / PLAN_AND_EXECUTE / SUPERVISOR
    int maxIterations,                // Skill Coach = 5
    Tier tierCeiling,                 // 该 Agent 想用的档位(受 Mode 上限二次夹紧)
    List<String> toolNames,           // 声明想用的工具(受 Mode 白名单二次过滤)
    ReflectionPolicy reflection,      // NONE / SELECTIVE / EVALUATOR / DUAL
    String systemPromptRef,           // 指向 prompt 资源
    List<String> skillRefs            // Procedural 记忆:挂哪些 skill 文件(§7.4)
) {}
```

**仲裁原则(技术 §5.2)**:AgentSpec 声明的是"想要什么",PermissionMode 定的是"上限"。运行时取交集:`effectiveTools = mode.whitelist ∩ spec.toolNames`;`effectiveTier = min(mode.tierCeiling, spec.tierCeiling)`。声明超限不报错,静默夹紧——因为 Mode 是安全边界,Agent 配置是偏好,安全边界永远赢。

`ToolSpec`/`HookSpec` 见 §7.1 / §5.3。

### 3.3 AgentSession —— 会话与 Working memory

一个 `chat_session` 对应一个 `AgentSession`,句柄放 Redis(技术 §6.1 Working 层):

- 键:`agent:session:{chatSessionId}`,存最近 N 轮消息窗口(不是全量,全量在 PG `chat_message`)、累计 token、当前 anchors。
- TTL:会话级(如 2h 滑动过期);过期后从 PG 重建窗口。
- **为什么 Redis 不直接读 PG**:每轮对话都要拼上下文,PG 分页拼历史是热路径上的重复 IO;Redis 存"已裁剪好的窗口",一次读到位。冷启动(Redis miss)才回 PG 重建。

---

## 4. Agent 路由(Coach Director)

### 4.1 阶段一:规则路由,不进 LLM

技术 §4.3 的关键判断:"角色 × 入口已决定九成路由"。所以 Director 阶段一是 `application` 层一个纯函数,不是 Agent:

```
入口路径 + JWT.role + 会话 anchors  ──►  agentType
```

| 入口 | role | 命中 agentType | 说明 |
|---|---|---|---|
| `POST /api/student/chat/{id}/ask` | STUDENT | `skill_coach` | 学生对话唯一 Agent(阶段一) |
| `POST /api/teacher/curriculum/**` | TEACHER | `curriculum` | 备课(二期,阶段一 501) |
| `/api/parent/**` | — | `family_coach`(禁用) | 恒 501(技术 §4.6) |

**只有"角色内意图细分"才需要判断**,而阶段一学生端只有一个 Agent,所以**阶段一没有真正的意图分类**——Director 就是一张静态表。这点要写死在文档里,避免有人一上来就接 qwen-turbo 做意图分类(A2:qwen 根本没配)。

### 4.2 二期:轻量 ReAct(iter=1)做角色内细分

当学生端出现第二个 Agent(如"训练计划 vs 技术答疑"分流),Director 升级为 LLM 意图分类:

- 模型:`TIER_FAST`(GLM `glm-4-flash`,替代文档里跑不了的 qwen-turbo)。
- 输出:结构化 `{agentType, confidence}`,低置信回落到默认 `skill_coach`。
- **无 Reflection**(技术 §4.3):路由错了用户重问即可,加 Reflection 不值。
- 分类结果记入 `chat_message.detail.routedAgent`(见审计 C7),供复盘。

### 4.3 路由的三个边界情况

1. **家长意图**:阶段一 Director 识别到家长语气也只返回预留提示,不路由到 family_coach(它禁用)。
2. **锚定失效**:请求带 `trainingSessionId` 但该 session 不属于当前学生 → 不是路由问题,是 Hook 的越权拦截(§5.3),Director 不管。
3. **跨 Agent handoff**:阶段一不做。二期若 Skill Coach 判断"这是备课问题"想转 Curriculum,走 `adapter/saa` 的 handoff,但**跨角色 handoff 禁止**(学生会话永不能 handoff 到教师 Agent),在 Mode 层拦。

---

## 5. Harness 关键子系统

### 5.1 PermissionMode 与 **Mode 决议规则**(补 §5.2 的缺口)

技术 §5.2 给了四种 Mode 的能力表,但没说**怎么从一个请求决议出 Mode**。补齐:

| Mode | 白名单 | Token 预算 | 档位上限 | **决议条件** |
|---|---|---|---|---|
| `STUDENT_OPEN` | 学生工具集 | 5K/会话 | TIER_FAST | role=STUDENT 且会话**未锚定** lesson/session(通用闲聊、原理问答) |
| `STUDENT_STRUCTURED` | 学生工具集 + 课程过滤 | 8K/会话 | TIER_STANDARD | role=STUDENT 且会话**锚定** trainingSessionId/lessonId(围绕某次训练答疑) |
| `TEACHER_LESSON` | 教师工具集 | 50K/会话 | TIER_ADVANCED | role=TEACHER |
| `PARENT_DIALOG`【预留】 | 家长工具集 | 30K/会话 | TIER_ADVANCED | role=PARENT(禁用,501) |

**决议函数**(application 层,进 Harness 前):

```
Mode decide(role, anchors):
  if role==TEACHER: return TEACHER_LESSON
  if role==STUDENT:
     return anchors.hasTrainingSessionOrLesson() ? STUDENT_STRUCTURED : STUDENT_OPEN
  if role==PARENT: return PARENT_DIALOG   // 后续被 501 拦
```

**为什么锚定与否决定 Mode**:锚定了训练 = 学生要"就这次投篮问为什么",值得更高档位(STANDARD)和更大预算(能多召回几条知识 + 读结论层),也值得开"课程过滤"白名单(工具只看这门课的数据);没锚定 = 泛问,给 FAST 省成本。这把技术 §5.2 那张静态表接上了真实入口。

`PermissionContext` 贯穿整条链:

```java
record PermissionContext(
    PermissionMode mode,
    UUID studentId,        // 数据主体(学生=本人;教师=不设,按工具参数)
    Role role,
    TokenBudget budget     // 剩余预算,LlmGateway 递减
) {}
```

### 5.2 LLM Gateway —— 统一收口(技术 §5.4)

一处收口所有 LLM 调用,五件事:档位映射、Token 预算、语义缓存、脱敏、降级。**阶段一 GLM 单供应商**(修正 A2/B2):

| Tier | 模型(GLM) | 用途 |
|---|---|---|
| `TIER_FAST` | `glm-4-flash` | Director 意图分类、闲聊、建议追问 |
| `TIER_STANDARD` | `glm-4-air` | Skill Coach 主力生成 |
| `TIER_ADVANCED` | `glm-4-plus` | 教师 Curriculum、Reflection Evaluator |

- **预算**:每次调用前 `budget.tryConsume(estimatedTokens)`,不足抛 `TOKEN_BUDGET_EXCEEDED(42910)`(接上 C3 的空错误码)。调用后按实际用量回填。预算计**整 prompt**(含 system + RAG 注入 + 历史窗口),不只可见回答(修正 D4)。
- **语义缓存**:对"客观知识类"问题(原理问答)做 embedding 近似缓存命中,直出不调 LLM。学生个人数据类问题**不缓存**(每人不同)。
- **脱敏**:出站 prompt 里学生姓名等 PII 用占位替换,回来再还原(避免 PII 进第三方模型日志)。
- **降级链**(单供应商版):`glm-primary → glm-fallback(降一档) → 语义缓存 → 兜底话术`。兜底话术是"AI 暂时不可用"而非报错,配 `LLM_UNAVAILABLE(50310)`。
- **异构双评**(Family Coach 的 Generator=Qwen/Evaluator=GLM)标为**二期**,随家长端与 dashscope 依赖一起上。

### 5.3 Hooks —— 数据隔离第二道防线(展开 §5.3)

`HookSpec` 声明一条 Pre/Post 拦截:

```java
record HookSpec(HookPhase phase, int order, HookHandler handler) {}
enum HookPhase { PRE_TOOL_USE, POST_TOOL_USE }
```

**PreToolUseHook(强制,不可关)**:每次工具调用前,拿 `ToolCall`(工具名 + 入参)与 `PermissionContext` 比对:

```
onPreToolUse(call, perm):
  reqStudentId = call.args["studentId"]
  if perm.role == STUDENT:
     if reqStudentId == null:
         call.args["studentId"] = perm.studentId      // 强制注入,学生工具不接受显式 studentId
     else if !reqStudentId.equals(perm.studentId):
         audit(DENY, perm, reqStudentId, call)         // 越权:写审计
         throw BusinessException(DATA_SCOPE_DENIED)     // 40301
  // 教师工具:studentId 是合法入参,但目标学生是否在其课程名单由工具内校验
  audit(ALLOW, perm, effectiveStudentId, call)
```

**为什么强制注入而不是信任入参**:学生端所有工具的 `studentId` 都来自会话上下文,**LLM 不应该能通过编造入参访问别人**。哪怕 web 层第一道闸失效(第一道在 JwtAuthFilter + 数据范围校验),这一道也拦得住。这就是技术 §5.3 说的"工具层防线"。

**PostToolUseHook(可选)**:用于结果脱敏(工具返回里若含他人姓名——如班级对比场景——按 Mode 决定裁剪)、结果体积截断(防超大 clip 列表撑爆上下文)、埋点。

Hook 执行是**责任链**,按 `order` 升序;任一 Pre Hook 抛异常则工具不执行。

### 5.4 中断与超时

- `interrupt(runId)`:学生点"停止"→ 停止 LLM 流、落已生成部分、`onDone(finishReason="interrupted")`。Plan-and-Execute 的中断是在检查点之间停(技术 §4.5),不是硬杀。
- SSE 生命周期:注册 completion/timeout/error 回调防泄漏(技术 §9.5),流中异常推 `error` 事件而非走 REST 异常处理器。

---

## 6. RAG 召回与 Memory

### 6.1 两条数据的分工(技术 §6.2 的边界)

- **RAG = 客观知识**:篮球原理、检查点的教学讲解与纠正练习。存 `checkpoint_knowledge`,按 `checkpoint_id` 挂接(所以 A1 词表统一是前提)。
- **Memory = 学生个人历史**:这个学生练过什么、上次说过什么。分四层(技术 §6.1):Working(Redis 会话)、Episodic(PG 向量,历史片段)、Semantic(PG,周批画像)、Procedural(Skill 文件,§7.4)。

一句话边界:**"投篮为什么要屈膝"走 RAG;"我上周投篮怎么样"走 Memory + 结论层工具。** Skill Coach 一轮里两者都可能用到,这正是技术 §4.4 说的"双路数据独特价值"。

### 6.2 Modular RAG 编排(技术 §6.2)

```
用户问句
  │
  ├─(意图判定:简单/复杂)──► 简单 → 直通道(跳过增强,直接向量检索 topK)
  │
  └─ 复杂 ─► Query 改写(消解指代/补上下文)
              └─► Multi-Query(生成 2~3 个子查询)
                    └─► 并行向量检索(PgVectorStore,余弦,embedding-3@1024)
                          └─► 合并去重 ─► Rerank(交叉编码/LLM 打分)─► topN 注入 prompt
```

- **直通道触发**:问句能直接映射到某个 `checkpoint_id`(如大屏刚提示"肘外翻",学生追问"肘外翻怎么改")→ 直接按该 checkpoint 查知识,不做 Multi-Query。省一轮 LLM。
- **检索域(多域)**:一期先做"检查点知识库"单域;`metadata` 里放 `domain` 字段,为二期(如"训练计划模板域")留扩展。
- **注入预算**:RAG 注入的片段计入 Token 预算(§5.2),STUDENT_OPEN(5K)最多注 2 段,STUDENT_STRUCTURED(8K)最多 4 段。

### 6.3 embedding 的写入与检索落点

严格按实体注释(`CheckpointKnowledge.java` / `EpisodicMemory.java`):**embedding 不经 JPA**(`@Transient`),由 Spring AI `PgVectorStore` 读写;实体只管元数据。

- **知识导入(离线/管理端)**:一条 `checkpoint_knowledge` 入库时,`content` 过 embedding-3 → `PgVectorStore.add(Document)`;元数据带 `checkpoint_id`/`domain`。导入前校验 `checkpoint_id ∈ vocabulary`(A1)。
- **检索**:`RagRetriever` 调 `PgVectorStore.similaritySearch(query, filter: checkpoint_id/domain)`,不写 JPA 查询。

### 6.4 Episodic memory 写入策略(补 C8)

"实时写只进 Episodic"(技术 §6.1),但**不能每轮都写**(会污染召回)。写入判据:

```
写一条 episodic memory 当且仅当:
  - 本轮触发了 Reflection(说明是损伤风险/长输出/不满等关键场景),或
  - 本轮 importance 打分 ≥ 阈值(LLM 轻量判"这段对话对理解该生是否重要"),且
  - 每会话上限 K 条(收敛,防单会话刷屏)
```

- `importance` 存 `episodic_memory.importance`(实体已有列)。
- Semantic 画像只由**周批单写者**更新(技术 §6.1 "天然免冲突"),读 Episodic 聚合成画像,不在对话热路径写。

---

## 7. Skill 与 Tools 调用

### 7.1 ToolSpec —— 工具是声明,不是散函数

```java
record ToolSpec(
    String name,                 // "GetRecentClips"
    String description,          // 给 LLM 看的用途(决定何时调用)
    Class<?> argsSchema,         // 结构化入参(JSON schema 由此生成)
    Tier costHint,               // 该工具典型成本(供预算预估)
    boolean requiresStudentScope,// true=受 PreToolUseHook 的 studentId 注入/校验
    ToolHandler handler
) {}
```

工具启动时注册进 `ToolRegistry`;Agent 只通过 `toolNames` 引用,不 new。**工具即业务**(技术 §2.1):handler 直接调 `application`/`repository`,不拆 Python 服务。

### 7.2 学生工具集(对齐技术 §4.4,只读结论层)

| 工具 | 读表 | 用途 | scope |
|---|---|---|---|
| `GetRecentClips` | `action_clip` | 最近若干次动作(边界/评分/进球) | ✔ |
| `GetSessionSummary` | `session_aggregate` | 某次课某动作的统计 | ✔ |
| `GetInstantFeedbackLog` | `instant_feedback` | 课中即时提示流水 | ✔ |
| `GetProgressTrend` | 跨 session 聚合 | 进步趋势(与 §6.5/§4.7 共用 SQL) | ✔ |
| `GetActionDetail` | `action_clip` 单条 | 单次动作细评(可含 phases) | ✔ |
| `SearchCheckpointKnowledge` | `checkpoint_knowledge`(RAG) | 检查点知识召回 | ✘(客观知识,无 scope) |

**红线(技术 §4.4)**:学生工具**只读结论层(action_clip / session_aggregate / instant_feedback),绝不碰逐帧 MotionRecord**(逐帧在 MinIO,LLM 用不上,技术 §7.2)。`requiresStudentScope=true` 的工具全部受 §5.3 Hook 管辖。

**为什么学生工具不接受显式 studentId**:见 §5.3——studentId 由 Hook 从会话强制注入,LLM 无法用编造的 id 访问他人。`SearchCheckpointKnowledge` 是唯一 `scope=✘` 的,因为知识是全校共享的客观内容。

### 7.3 教师工具集(阶段二,Curriculum)

| 工具 | 用途 |
|---|---|
| `GetLessonRoster` | 读某课名单(受 lesson 归属校验) |
| `GetClassSummary` | 班级维度汇总(复用 §8.1 聚合) |
| `GenerateLessonDoc` | Java Tool(Apache POI/docx4j)产出 Word/PPT(技术 §4.5) |
| `SaveLessonPlan` | 写 async_tasks 检查点(Plan-and-Execute 中断续做) |

教师工具的 studentId 是**合法入参**(教师要看多个学生),但目标学生必须在该教师课程名单内,由工具内校验(不是 Hook 强制注入,因为教师本就跨学生)。归属校验口径同教师端业务逻辑 §2。

### 7.4 Skill 文件 = Procedural memory(技术 §6.1)

Skill 文件承载**方法论/流程**(如"如何讲解一个检查点""教案生成的步骤"),是静态的 Procedural 记忆,`AgentSpec.skillRefs` 挂接。

- **与 RAG 的区别**:RAG 是"可检索的知识片段"(按问句召回);Skill 是"固定挂载的做事流程"(整篇进 system prompt 或按需加载)。Skill Coach 挂"技术答疑方法论";Curriculum 挂"教案方法论"(技术 §4.5:Skill 文件只承载方法论,不依赖脚本沙箱)。
- **载体**:一期用 classpath 资源(`skills/skill_coach/*.md`),不引外部脚本沙箱。文档生成能力在 Java Tool 里(§7.3),Skill 文件只讲"怎么组织教案",不执行代码。

### 7.5 一次工具调用的完整链路

```
LLM 决定调用 GetRecentClips{limit:5}
  │
  ├─ ToolRegistry 解析 name → ToolSpec
  ├─ PreToolUseHook:注入/校验 studentId(§5.3)→ 通过则写 audit(ALLOW)
  ├─ handler 执行:application → repository → 结论层表
  ├─ PostToolUseHook:脱敏/截断(§5.3)
  ├─ 结果回 LLM(sink.onToolCall label="查看最近5次投篮")
  └─ LLM 据结果继续 ReAct 下一步
```

`sink.onToolCall` 的 `label` 是给学生看的中文短句(`ChatToolEvent.label`),不是工具名——学生看到"正在查看你最近的投篮",不是 `GetRecentClips`。

---

## 8. 日志审计的 boot 详细设计

### 8.1 统一的 audit_log(落地 B1 的结论)

一张表服务两类审计,字段以实体 `AuditLog.java` 为准,教师文档的 `target_type/target_id` 废弃:

| 列 | 语义 | 建档审计示例 | 工具审计示例 |
|---|---|---|---|
| `account_id` | 操作者 | 教师 id | 学生 id(或教师) |
| `action` | 动词枚举 | `STUDENT_CREATE` | `TOOL_INVOKE` / `TOOL_DENY` |
| `target_student_id` | 数据主体 | 新建学生 id | 被访问学生 id |
| `detail`(jsonb) | 上下文 | `{studentNo,lessonId}` | `{toolName,agentType,chatSessionId,requestedStudentId,decision}` |
| `created_at` | 时间 | 自动 | 自动 |

**"越权一查即现"如何实现**(技术 §8.1 承诺):越权 = `account_id` 对应的学生 ≠ `target_student_id` 且 `action='TOOL_DENY'`。一条 SQL 即可捞出所有越权尝试。

### 8.2 谁在什么时候写(boot 触发点)

| 触发点 | action | 由谁写 | 事务 |
|---|---|---|---|
| §5.7 导入顺带建档 | `STUDENT_AUTO_CREATE` | `StudentProvisioningService` | 与建档同事务 |
| §6.1 单独建档 | `STUDENT_CREATE` | `StudentManageService` | 与建档同事务 |
| 工具调用放行 | `TOOL_INVOKE` | PreToolUseHook | **独立事务**(见下) |
| 工具越权拦截 | `TOOL_DENY` | PreToolUseHook | **独立事务** |
| 生物特征采集 | `GALLERY_ENROLL` | edge → ingest | ingest 事务 |

**工具审计为什么用独立事务(`REQUIRES_NEW`)**:越权拦截会抛 `DATA_SCOPE_DENIED` 回滚业务事务;如果审计和业务同事务,越权记录会**跟着回滚掉**——正好丢掉最该留的那条。所以 Hook 写审计用新事务,业务回滚不影响它。这是审计 boot 最容易埋的坑,必须写死。

**技术异常兜底**:审计写失败**不能阻断业务**(除越权拦截本身)。审计写用 try-catch 包裹,失败记 ERROR 日志 + metrics,不向上抛。

### 8.3 与技术 §9.5 多线程要点的咬合

- **上下文传播**:`PermissionContext`(含 studentId/traceId)在 SSE 异步、Reflection 重生成、Multi-Query 并行检索等线程切换处**必须显式传递**(技术 §9.5:"断了是安全漏洞")。Harness 用 `AgentInvocation` 显式带上下文贯穿,不依赖 ThreadLocal 自动传播。
- **事务边界在 application**(技术 §9.5):事务内不调 LLM/HTTP/MQ。工具 handler 若要写库,自身短事务;LLM 调用永远在事务外。

---

## 9. Boot 装配顺序(Spring 上下文启动)

```
1. VocabularyLoader        读 contracts/vocabulary.json,校验自洽(A1 前提)
2. LlmGateway              装 GLM ChatModel + 档位表 + 预算/缓存/脱敏/降级(§5.2)
3. PgVectorStore           embedding-3@1024,余弦(RAG/Memory 共用)
4. ToolRegistry            扫描并注册所有 ToolSpec(§7.1)
5. HookChain               注册 PreToolUseHook(强制)+ 可选 Post(§5.3)
6. AgentRegistry           注册 AgentSpec(skill_coach 阶段一;curriculum 禁用占位)
7. PermissionArbiter       Mode 决议 + 白名单/档位仲裁(§5.1)
8. AgentRuntime(adapter/saa 实现) 组装以上,暴露 execute/stream/interrupt
9. RouterService(application)静态路由表(§4.1)
10. Chat/Curriculum Controller(web) 接 SSE(cloud-agent-api)
```

**顺序不能反的两处**:VocabularyLoader 必须最先(4/6 都依赖词表自洽);HookChain 必须在 AgentRuntime 之前(运行时要拿到强制 Hook)。若 `hoopshake.agent.enabled=false`(开发机无 GLM key),1~10 全部跳过,`/api/student/chat/**` 返回 `LLM_UNAVAILABLE(50310)` 而非启动失败——和 edge 的 `cloud.base-url 留空则跳过`(Edge §9.3)同一思路:缺依赖降级,不阻断启动。

---

## 10. 与现有代码的差异清单(实现时对照)

| 项 | 现状 | 目标态 | 出处 |
|---|---|---|---|
| 词表 | 三处硬编码不一致 | `contracts/vocabulary.json` 唯一权威 | A1 |
| LLM 供应商 | 只有 zhipuai(GLM) | GLM 单供应商档位表;Qwen 二期 | B2/§5.2 |
| SAA | 未引入 | `adapter/saa` 隔离;可先用 Spring AI 原生 | B3/§1 |
| Chat 持久层 | 无 `Chat*Repository` | 建 repo + AgentSession(Redis) | C5/§3.3 |
| Token 预算 | 错误码 42910 无执行点 | LlmGateway 递减 + 拦截 | C3/§5.2 |
| Hooks | 一句话 | PreToolUseHook 强制注入/越权拦截 | C6/§5.3 |
| audit_log | 两套字段 | 统一实体字段,独立事务写 | B1/§8 |
| `chat_message` | 无元数据列 | 加 `detail` jsonb 记 routedAgent 等 | C7 |

实现前请再过一遍 `../design-audit.md` §E 的顺序:**先修地基(1~3),再建 Harness(4),最后接 Agent 与端点。**
