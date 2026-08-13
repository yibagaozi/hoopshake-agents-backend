# HOOPSHAKE 云端 Agent 系统详细设计

`v1.1 · 2026-08-13 · cloud 包 · 面向学生/教师两个前端`

本篇把技术参考手册 §4/§5/§6 的"组件总览 + 设计哲学"落成**可实现的抽象、编排流程与装配顺序**,回答六个具体问题:如何编排 Agent、如何调用 Skill、如何调用 Tool、如何实现 Harness、如何做日志审计、如何调用 RAG(含**文档导入 / 切分 / 灌库**)。

**本篇范围**:只覆盖 `cloud` 包的 Agent 部分,以及面向**学生端 / 教师端**两个前端的服务。**不涉及**:edge、算法侧、词表权威化、ingest 包位置/迁移、MQ 通道——这些另行处理,本篇一律当作"外部已给定的输入"(如 `checkpoint_id` 只是一个字符串标签,不追问它由谁定义)。

配套:对外 HTTP/SSE 契约见 `cloud-agent-api.md`;学生端产品流程见 `../product/student-agent-ux.md`;本轮范围内的设计问题见 `../design-audit.md`。

> 状态:**设计讨论稿。本轮不产出代码;讨论通过后再单独生成实现。** 凡与现有代码冲突处,本篇以"目标态"描述并在 §12 列差异。

---

## 0. 设计立场(三条不变量)

1. **框架可换,业务不动。** 业务层(`web`/`application`)只认 `AgentRuntime`,永不 import Spring AI / SAA 类型。编排框架是 `harness/adapter` 里的实现细节,ArchUnit 强制。理由:Spring AI 2.0-M4 / SAA 都在 M 版,不能让预览版 API 渗进业务代码——换版本或换框架时只改一个包。
2. **能用规则不用 Workflow,能用 Workflow 不用 Agent。**(技术 §3.3)Director 阶段一是规则路由不是 LLM;只有"学生问题千变万化、需按中间结果调整"这类场景才上 ReAct Agent。
3. **学生只能看到自己的数据,三层各设一道闸。** web 鉴权(第一道)、Harness Hook 强制注入 studentId(第二道)、audit_log 事后可查(第三道)。任一层被绕过,另两层兜底。

---

## 1. 分层与包结构(cloud 内部)

技术 §9.1 的 `web → application → harness`,本篇把 `harness` 展开:

```
cloud/src/main/java/com/cnsportiot/cloud/
  web/            REST/SSE 控制器、鉴权、参数校验        ── 不 import harness.adapter
  application/    编排入口、事务、studentId 注入、Mode 决议、路由
  harness/
    AgentRuntime            业务侧唯一入口(execute/stream/interrupt)
    spec/                   AgentSpec / ToolSpec / HookSpec(声明式 record)
    permission/             PermissionMode + 仲裁器
    hook/                   Pre/PostToolUseHook 链
    session/                AgentSession(Working memory 句柄,Redis)
    llm/                    LlmGateway(档位/预算/缓存/脱敏/降级)
    tool/                   ToolRegistry + 各业务工具实现
    rag/                    ingest/(导入·切分·灌库) + retrieve/(召回编排)
    memory/                 MemoryStore(Episodic/Semantic)
    adapter/spring/         ★唯一允许 import Spring AI / SAA 编排类型的包(ArchUnit)
  domain/         实体、枚举
```

**ArchUnit 规则(测试期)**:`no classes outside ..harness.adapter.. should depend on org.springframework.ai.. or com.alibaba.cloud.ai..`。RAG 的 `PgVectorStore`、`ChatClient`、`DocumentReader`/`TextSplitter` 全部只在 `adapter/spring` 出现,`rag`/`tool`/`memory` 通过 harness 自己的接口调它。

---

## 2. 组件清单(阶段一交付边界)

| 组件 | 类型 | 执行模式 | Reflection | 阶段一 | 落点 |
|---|---|---|---|---|---|
| **Coach Director** | Supervisor | 规则路由 | ❌ | ✅ | `application.RouterService` |
| **Skill Coach** | ReAct Agent | ReAct(iter≤5) | ⚠️ 选择性 | ✅ 主力 | `AgentSpec("skill_coach")` |
| **Curriculum** | ReAct Agent | Plan-and-Execute | ✅ Evaluator | 二期 | `AgentSpec("curriculum")` |
| Family Coach | ReAct Agent | 双层 Reflection | ✅ 强制 | 【预留】501 | 禁用态 |
| Commentary | 单次 LLM | — | ❌ | 后期 | `LlmGateway.oneShot()` |

**阶段一真正跑起来的只有:规则路由 + Skill Coach(学生主力)。** Curriculum 是二期,其余是预留位。技术 §3.3 的"5 Agent + 3 Workflow"是目标态,不是一期清单。

---

## 3. Harness 运行时:如何实现 Harness

### 3.1 AgentRuntime —— 业务侧唯一入口

```java
public interface AgentRuntime {
    AgentResult execute(AgentInvocation inv);              // 同步:教师简单查询/内部调用
    void        stream(AgentInvocation inv, AgentEventSink sink); // 流式:学生对话主路
    void        interrupt(String runId);                  // 中断:点停止 / 改 Plan
}
```

`AgentInvocation` 由 `application` 层组装(harness 不碰 web/HTTP):

```java
record AgentInvocation(
    String runId,               // 本次运行唯一 id,interrupt 用
    String agentType,           // 路由结果:"skill_coach"...
    PermissionContext perm,     // Mode + 预算 + 数据主体 studentId(§5.1)
    UUID chatSessionId,         // Working memory 键
    String userText,
    Map<String,Object> anchors  // trainingSessionId / lessonId / clipId(可选)
) {}
```

`AgentEventSink` 是流式事件下游,web 接到 SSE,事件与 `ChatDtos` 一一对应:

| Sink 回调 | ChatDtos | 时机 |
|---|---|---|
| `onMeta(messageId,sessionId)` | `ChatMetaEvent` | 落 ASSISTANT 空壳后 |
| `onToolCall(name,status,label)` | `ChatToolEvent` | 工具进入/完成 |
| `onDelta(text)` | `ChatDeltaEvent` | LLM 逐 token |
| `onDone(finishReason,tokens,suggestions)` | `ChatDoneEvent` | 收尾 |

**为什么用 sink 不返回 `Flux`**:业务层不被 Reactor 绑架(同"不 import 框架类型")。底层用 SSE、虚拟线程 + 阻塞写都行。

### 3.2 声明式:AgentSpec / ToolSpec / HookSpec

Agent 是一份**声明**,启动时注册进 `AgentRegistry`:

```java
record AgentSpec(
    String type,                 // "skill_coach"
    ExecutionMode mode,          // REACT / PLAN_AND_EXECUTE / SUPERVISOR
    int maxIterations,           // Skill Coach = 5
    Tier tierCeiling,            // 想用的档位(受 Mode 上限二次夹紧)
    List<String> toolNames,      // 想用的工具(受 Mode 白名单二次过滤)
    ReflectionPolicy reflection, // NONE / SELECTIVE / EVALUATOR / DUAL
    String systemPromptRef,      // prompt 资源
    List<String> skillRefs       // 挂哪些 skill 文件(§6)
) {}
```

**仲裁原则**:AgentSpec 声明"想要什么",PermissionMode 定"上限",运行时取交集——`effectiveTools = mode.whitelist ∩ spec.toolNames`;`effectiveTier = min(mode.ceiling, spec.tierCeiling)`。声明超限不报错,静默夹紧,因为安全边界永远赢。

### 3.3 AgentSession —— Working memory(Redis)

一个 `chat_session` 对应一个 `AgentSession`,句柄放 Redis(技术 §6.1 Working 层):

- 键 `agent:session:{chatSessionId}`,存**已裁剪的最近 N 轮窗口**、累计 token、当前 anchors。全量历史在 PG `chat_message`。
- TTL 会话级滑动过期;miss 时从 PG 重建窗口。
- **为什么不每轮读 PG**:拼上下文是热路径,PG 分页拼历史是重复 IO;Redis 存"拼好的窗口"一次读到位。

### 3.4 Harness 如何映射到 Spring AI(adapter/spring 具体实现)

`AgentRuntime` 的默认实现 `SpringAiAgentRuntime` 落在 `adapter/spring`,是唯一碰 `ChatClient` 的地方:

```
AgentRuntime.stream(inv, sink)
  └─ SpringAiAgentRuntime:
       1. 取 AgentSpec(inv.agentType)
       2. 仲裁:effectiveTools / effectiveTier(§3.2)
       3. 组 ChatClient:
            .system(prompt = systemPrompt + 注入的 skill 文件(§6))
            .tools(toolCallbacks)     ← ToolSpec 适配成 Spring AI ToolCallback(§7)
            .advisors(...)            ← 可选:MessageChatMemoryAdvisor(窗口)/ QA advisor
            .options(model = tier→GLM 型号, maxTokens = 预算剩余)
       4. .stream() 驱动;逐 token → sink.onDelta;工具调用 → Hook 拦截(§7.4)→ sink.onToolCall
       5. Reflection 策略命中则二次生成(§4.4)
       6. 收尾 sink.onDone;落 chat_message + 累计 token 回填预算
```

**ReAct 循环由谁驱动**:Spring AI 的 `ChatClient` + tool calling 本身会在"模型请求调用工具 → 执行 → 回灌结果 → 模型继续"之间自动循环。Harness 只需:(a) 设 `maxIterations` 上限防打转,(b) 每次工具调用前后插 Hook,(c) 把中间事件转成 sink。**不需要自己手写 ReAct 状态机**——这正是用 Spring AI 的原因。SAA 的 supervisor/handoff 是二期在同一 adapter 里可替换的更高级编排。

---

## 4. 如何编排 Agent

### 4.1 路由(Coach Director)—— 阶段一是静态表

"角色 × 入口已决定九成路由"(技术 §4.3),所以 Director 阶段一是 `application` 层纯函数,**不进 LLM**:

| 入口 | role | agentType |
|---|---|---|
| `POST /api/student/chat/sessions/{id}/ask` | STUDENT | `skill_coach` |
| `POST /api/teacher/curriculum/**` | TEACHER | `curriculum`(二期,阶段一 501) |
| `/api/parent/**` | — | 恒 501 |

阶段一学生端只有一个 Agent,所以**没有真正的意图分类**,Director 就是这张表。二期出现第二个学生 Agent 时,才升级为轻量 LLM 分类(`TIER_FAST`,输出 `{agentType,confidence}`,低置信回落默认;无 Reflection——路由错了重问即可)。分类结果记 `chat_message.detail.routedAgent` 供复盘。

### 4.2 ReAct 编排(Skill Coach,阶段一主力)

学生问题无法预先规划,每步基于上步发现,典型 ReAct(技术 §4.4)。一次 ask 的编排:

```
用户: "我最近投篮怎么样,老感觉不稳"
  │
  iter1  LLM 思考 → 决定调 GetProgressTrend{actionType:jump_shot}
         Hook 注入 studentId → 执行 → 命中"命中率近三次下滑"
  iter2  LLM 决定调 GetInstantFeedbackLog → 见多次"肘外翻/出手时机晚"
  iter3  LLM 决定调 SearchKnowledge{query:"投篮肘外翻 怎么纠正"}(RAG,§8;纯语义,不带 checkpoint)
         召回"辅助手/发力链 纠正练习"片段
  iter4  LLM 综合三路数据生成回答(双路数据结合,技术 §4.4)
         → Reflection 策略判定(涉动作纠正/长输出)→ 命中则自评一次(§4.4)
  done   落库 + 建议追问
```

- **迭代上限** `maxIterations=5`:防 LLM 反复调工具打转;到顶强制收敛出答复。
- **工具选择由 LLM 自主**:Harness 只提供工具清单和描述(`ToolSpec.description`),不写死"先查 A 再查 B"。这是 ReAct 与 Workflow 的分界。
- **上下文窗口**:每轮把 AgentSession 的窗口 + 本轮工具结果一起给 LLM;窗口超长时按 token 预算裁最旧轮次。

### 4.3 Plan-and-Execute 编排(Curriculum,二期)

教案任务边界明确、步骤多(5~15)、教师想先看大纲再决定、要支持"改 Plan 后继续"——ReAct 做不到中断修改(技术 §4.5)。编排:

```
计划阶段  LLM 出大纲 → 落 async_tasks(状态 PLAN_READY,plan=大纲)→ 停,等教师
   │      (中断点:教师可改 plan,PUT 回写)
执行阶段  教师确认 → 逐步执行大纲,每步一个检查点写回 async_tasks
   │      (可续做:进程重启/中断后从最后检查点继续)
产出      GenerateLessonDoc 工具产 Word/PPT → DONE
```

- **不依赖图框架**(技术 §4.5):用自建 `async_tasks` 状态机 + 检查点。`interrupt(runId)` 在检查点之间停,不是硬杀。
- **Reflection = Evaluator**:每步产出过一次质量评估(教案质量把关)。

### 4.4 Reflection 编排(按风险分级,技术 §4.2)

| Agent | 策略 | 触发 | 实现 |
|---|---|---|---|
| Skill Coach | `SELECTIVE` | 涉损伤风险/长输出(>500字)/用户不满/涉计划修改 | 同模型换 prompt 自评 4 维(完整性/格式/有害/可懂),不通过重生成**最多 1 次**,成本 +20% |
| Curriculum | `EVALUATOR` | 每步产出 | 专门 Evaluator prompt 打分,不达标重做该步 |
| Director/闲聊/查历史 | `NONE` | — | 不触发,省成本 |
| Family Coach | `DUAL`(异构) | 全程 | 【预留】二期,Generator/Evaluator 异构供应商 |

Reflection 是 Harness 里 `AgentRuntime` 收尾前的一个可选环节,由 `AgentSpec.reflection` 声明,业务无感。

---

## 5. Harness 子系统

### 5.1 PermissionMode 与决议规则

| Mode | 白名单 | Token 预算 | 档位上限 | 决议条件 |
|---|---|---|---|---|
| `STUDENT_OPEN` | 学生工具集 | 5K/会话 | TIER_FAST | STUDENT 且会话**未锚定** |
| `STUDENT_STRUCTURED` | 学生工具集+课程过滤 | 8K/会话 | TIER_STANDARD | STUDENT 且**锚定** trainingSessionId/lessonId |
| `TEACHER_LESSON` | 教师工具集 | 50K/会话 | TIER_ADVANCED | TEACHER |
| `PARENT_DIALOG`【预留】 | 家长工具集 | 30K/会话 | TIER_ADVANCED | PARENT(禁用) |

```
Mode decide(role, anchors):
  TEACHER → TEACHER_LESSON
  STUDENT → anchors.hasTrainingOrLesson() ? STUDENT_STRUCTURED : STUDENT_OPEN
  PARENT  → PARENT_DIALOG   // 后续 501
```

**为什么锚定决定 Mode**:锚定训练 = 学生要"就这次投篮问为什么",值得更高档位、更大预算(能多召回几段知识)、开"课程过滤"白名单(工具只看这门课数据);未锚定 = 泛问,给 FAST 省成本。

`PermissionContext{mode, studentId, role, budget}` 贯穿整条链。

### 5.2 LLM Gateway(GLM 单供应商)

一处收口所有 LLM 调用:档位映射、预算、缓存、脱敏、降级。阶段一 **GLM 单供应商**:

| Tier | GLM 型号 | 用途 |
|---|---|---|
| `TIER_FAST` | `glm-4-flash` | 路由分类、闲聊、建议追问、query 改写 |
| `TIER_STANDARD` | `glm-4-air` | Skill Coach 主力生成 |
| `TIER_ADVANCED` | `glm-4-plus` | Curriculum、Reflection Evaluator |

- **预算**:调用前 `budget.tryConsume(estimated)`,不足抛 `TOKEN_BUDGET_EXCEEDED(42910)`;调用后按实际回填。计**整 prompt**(system + skill + RAG 注入 + 历史窗口),不只可见回答。
- **语义缓存**:客观知识类问题(原理问答)按 embedding 近似命中直出;个人数据类问题不缓存。
- **脱敏**:出站 prompt 里姓名等 PII 占位替换,回来还原(PII 不进第三方模型日志)。
- **降级链**:`glm-primary → glm 降一档 → 语义缓存 → 兜底话术(LLM_UNAVAILABLE 50310)`。

> 异构双评(Family Coach)与多供应商是二期,不阻塞阶段一。

### 5.3 Hooks —— 数据隔离第二道防线

```java
record HookSpec(HookPhase phase, int order, HookHandler handler) {}
enum HookPhase { PRE_TOOL_USE, POST_TOOL_USE }
```

**PreToolUseHook(强制,不可关)**:

```
onPreToolUse(call, perm):
  if perm.role == STUDENT and call.spec.requiresStudentScope:
     if call.args.studentId == null:
         call.args.studentId = perm.studentId          // 强制注入
     else if call.args.studentId != perm.studentId:
         audit(TOOL_DENY, perm, requested, call)         // 越权:写审计(§9)
         throw BusinessException(DATA_SCOPE_DENIED)       // 40301
  audit(TOOL_INVOKE, perm, effectiveStudentId, call)
```

**为什么强制注入而非信任入参**:学生工具的 studentId 只能来自会话,**LLM 不该能编造入参访问别人**。哪怕 web 第一道闸失效,这道也拦得住。

**PostToolUseHook(可选)**:结果脱敏(班级对比场景裁剪他人姓名)、体积截断(防超大列表撑爆上下文)、埋点。

Hook 是责任链,按 `order` 升序;任一 Pre Hook 抛异常则工具不执行。

### 5.4 中断与超时

- `interrupt(runId)`:停 LLM 流、落已生成部分、`onDone(finishReason="interrupted")`。P&E 在检查点之间停。
- SSE 注册 completion/timeout/error 回调防泄漏(技术 §9.5);流内异常推 `error` 事件而非走 REST 异常处理器。

---

## 6. 如何调用 Skill(Procedural memory)

**Skill 文件 = 做事的方法论/流程**(技术 §6.1 Procedural 层),静态、按 Agent 挂载。与 RAG 的区别:

| | Skill | RAG |
|---|---|---|
| 内容 | "怎么做"的流程(如"如何讲解一个检查点") | "是什么"的知识片段(篮球原理) |
| 载入 | **固定挂载**,整篇进 system prompt(或按名加载) | **按问句召回** topN 片段 |
| 变化 | 静态,随版本发布 | 可持续导入更新 |

### 6.1 载体与加载

- 一期用 **classpath 资源**:`cloud/src/main/resources/skills/{agentType}/*.md`,不引外部脚本沙箱(技术 §4.5:Skill 只承载方法论,不跑脚本)。
- `AgentSpec.skillRefs = ["skill_coach/explain_checkpoint.md", "skill_coach/injury_safe_advice.md"]`。
- 启动时 `SkillLoader` 读入并缓存;`SpringAiAgentRuntime` 组 ChatClient 时把挂载的 skill 文本拼进 system prompt(§3.4 第 3 步)。

### 6.2 何时整篇挂 vs 何时按需加载

- **短方法论(<1~2K token)**:整篇挂进 system。Skill Coach 阶段一走这个,简单可靠。
- **长方法论(如 Curriculum 的完整教案方法论)**:做成"可被 LLM 按名调取的 skill 工具"(`LoadSkill{name}`),避免每轮都占满上下文。二期随 Curriculum 落地。

Skill 内容计入 Token 预算(§5.2)——挂太多 skill 会挤占 RAG 注入与历史窗口,`AgentSpec` 评审时要算这笔账。

---

## 7. 如何调用 Tool

### 7.1 ToolSpec → Spring AI 绑定

```java
record ToolSpec(
    String name,                  // "GetRecentClips"
    String description,           // 给 LLM 看的用途(决定何时调用)——写好它是工具能被正确调用的关键
    Class<?> argsSchema,          // 结构化入参(JSON schema 由此生成给 LLM)
    Tier costHint,                // 典型成本(供预算预估)
    boolean requiresStudentScope, // true=受 PreToolUseHook 的 studentId 注入/校验
    ToolHandler handler           // 实际执行:调 application/repository
) {}
```

启动 `ToolRegistry` 扫描注册。`adapter/spring` 把每个 ToolSpec 适配成 Spring AI 的 `ToolCallback`(用 `argsSchema` 生成入参 schema,`handler` 作为回调体),`ChatClient.tools(...)` 注册。**工具即业务**(技术 §2.1):handler 直接调 repository/service,不拆 Python 服务。

### 7.2 学生工具集(只读结论层)

| 工具 | 读表 | scope |
|---|---|---|
| `GetRecentClips` | `action_clip` | ✔ |
| `GetSessionSummary` | `session_aggregate` | ✔ |
| `GetInstantFeedbackLog` | `instant_feedback` | ✔ |
| `GetProgressTrend` | 跨 session 聚合(与 §4.7/§6.5 共用 SQL) | ✔ |
| `GetActionDetail` | `action_clip` 单条 | ✔ |
| `SearchKnowledge` | RAG(§8.5) | ✘(客观知识,无 scope) |

**红线**(技术 §4.4):学生工具只读结论层(action_clip / session_aggregate / instant_feedback),**绝不碰逐帧 MotionRecord**(在 MinIO,LLM 用不上)。`requiresStudentScope=true` 全受 §5.3 Hook 管;`SearchKnowledge` 是唯一 scope=✘,因知识全校共享。

### 7.3 教师工具集(二期,Curriculum)

| 工具 | 用途 |
|---|---|
| `GetLessonRoster` | 读某课名单(受 lesson 归属校验) |
| `GetClassSummary` | 班级汇总(复用教师端 §8.1 口径) |
| `GenerateLessonDoc` | Java Tool(POI/docx4j)产 Word/PPT |
| `SaveLessonPlan` | 写 async_tasks 检查点(P&E 续做) |

教师工具的 studentId 是**合法入参**(教师跨多个学生),但目标学生必须在其课程名单内,由工具内校验(不是 Hook 强制注入)。归属校验口径同教师端业务逻辑 §2。

### 7.4 一次调用全链路

```
LLM 决定 GetRecentClips{limit:5}
  → ToolRegistry 解析 name → ToolSpec
  → PreToolUseHook: 注入/校验 studentId(§5.3)→ audit(TOOL_INVOKE)
  → handler: application → repository → 结论层表
  → PostToolUseHook: 脱敏/截断
  → 结果回 LLM;sink.onToolCall(label="查看你最近5次投篮")
  → LLM 据结果继续 ReAct
```

`label` 是给学生看的中文短句(`ChatToolEvent.label`),不是工具名。

---

## 8. 如何调用 RAG(含导入 / 切分 / 灌库)

> 本节是本轮重点。RAG = 客观知识(篮球原理、检查点讲解、纠正练习);Memory = 学生个人历史(§8.7)。边界一句话:**"投篮为什么屈膝"走 RAG;"我上周投篮怎样"走 Memory + 结论层工具。**

### 8.0 存储决策(✅ 已定:方案 A)

用 **Spring AI `PgVectorStore` 自管表**为 RAG 的**唯一存储**(chunk 内容 + metadata + embedding 三合一),不走 JPA 双写——呼应技术 §8.2"pgvector 一站式,免维护双存储一致性"。

- 知识库:一个 VectorStore bean → 表 `knowledge_vector_store`。
- 记忆库:另一个 VectorStore bean → 表 `memory_vector_store`(§8.7;隐私与访问模式不同,分表)。
- **`CheckpointKnowledge` / `EpisodicMemory` 两个 JPA 实体的 chunk 存储职责废弃**:chunk 全交 VectorStore;另置一张轻量 catalog 表 `knowledge_document`(记 `doc_id / source / domain / checkpoint_id?(暂空,见 §8.1)/ version / chunk_count / split_params / content_hash / imported_at`)供"哪些源文档已导入、便于重导与下架"。**记忆不需要 catalog**(按学生查即可)。

Spring AI `PgVectorStore` 表结构(它自建):`id uuid, content text, metadata jsonb, embedding vector(1024)`;相似度 `COSINE`(与技术 §6.2 一致)。

### 8.1 导入(Import)—— 文档从哪来、怎么进来

**知识来源**(两类,结构差异大,切分要都能处理,见 §8.3):
- **教科书**:连续散文,层级标题 + 段落(示例:"投篮技术 › (一)原地投篮 › 1.原地立定投篮 › 技术动作要点…")。
- **团队整理稿**:高度结构化,按"优先级 N(错误类型)"分块,每块含 `错误表现 / 纠正核心 / 动作要领(若干子点)/ 教练口诀`(示例:"原地罚篮 8 项 › 优先级2 辅助手参与发力")。

格式 markdown / docx / pdf / txt。

**⚠ checkpoint 暂不对齐**:算法侧检查点体系尚未就位,整理稿目前**无法对齐 `checkpoint_id`**。故本阶段**一律按普通文本导入**,`checkpoint_id` 元数据留空;召回走**纯语义检索**(§8.5),不依赖 checkpoint。将来算法侧就位后,用 §8.1 的 `reindex` 或元数据回填给 chunk 补 `checkpoint_id` 即可,**存储与切分设计不受影响**。

**两个入口**:

1. **启动种子加载(boot seed)**:`KnowledgeSeedLoader` 扫描 `classpath:knowledge/**`,启动时确保种子知识入库,**幂等**(按 `doc_id + content_hash` 跳过未变化)。开发/首发用。
2. **管理 API(运维/教研,非学生/教师前端,role=ADMIN)**:
   - `POST /api/admin/knowledge/documents` 上传/登记一篇文档 → **异步**切分灌库,返回 `{taskId}`;`GET .../tasks/{taskId}` 轮询 `{status, docId, chunks}`。(异步:大文档 embedding 慢,不阻塞请求;与教师端 Curriculum 的 async_tasks 复用同一状态机。)
   - `GET /api/admin/knowledge/documents` 列已导入源文档(读 catalog)。
   - `DELETE /api/admin/knowledge/documents/{docId}` 下架(删该 doc 全部 chunk,§8.4)。
   - `POST /api/admin/knowledge/documents/{docId}/reindex` 重切重灌(改切分参数、换 embedding 模型、或将来补 checkpoint 对齐时)。

> 管理端能力,不属学生/教师两个前端。前端**只消费召回**,不导入。

每篇文档元数据:`doc_id`、`source`(`textbook` / `team`)、`domain`(如 `shooting`)、`checkpoint_id`(**当前留空**)、`version`、`section_title`。

### 8.2 读取与解析(Spring AI DocumentReader)

`adapter/spring` 里:

- markdown → `MarkdownDocumentReader`(保留标题层级为 metadata);
- pdf/docx → `TikaDocumentReader`;
- txt → `TextReader`。

产出 `List<Document>`(每篇通常先是一个大 Document,再进切分)。

### 8.3 切分(Chunking)—— 怎么切、切多大(按你的两份样本定)

**为什么切**:召回要"命中最相关的一段"塞进 prompt(受预算 §5.2),不是整篇灌进去。切得太大→一个 chunk 混了多个主题,召回被无关内容稀释;切得太小→一句话丢了上下文。

**先量样本的自然语义单元(这决定 chunk 大小,不是拍脑袋定 token 数)**:

| 样本 | 自然单元 | 单元规模(中文字≈0.9~1.3 token) |
|---|---|---|
| 整理稿 | 一个"优先级 N"块(错误表现+纠正核心+动作要领+口诀) | ~200~260 字 ≈ **200~320 token** |
| 教科书 | 一个技术小节(如"原地立定投篮")或其中一个标注段(如"技术动作要点") | 小节 ~320~430 token;单段 ~50~150 token |

结论:**两份语料的语义单元都落在 ~200~430 token,没有超过 ~450 的**。所以真正起作用的是"**在语义边界切、不要切碎也不要跨单元合并**",token 数只是防跑飞的上限。

**策略:结构优先(硬边界)+ token 上限(兜底)**:

1. **结构切(硬边界,绝不跨越)**:按有序分隔符递归切——`①标题(一、/ 1. / (一))` → `②"优先级 N" 标记` → `③空行分段` → `④句末。`。
   - 整理稿:**每个"优先级 N"块 = 一个 chunk**(它本身就是一个完整、可召回的纠正建议;把"辅助手"块和"下肢发力"块合进一个 chunk 会让召回不纯)。
   - 教科书:**每个技术小节 = 一个 chunk**;小节若超上限,再按标注段("技术动作要点"/"持球手法"/…)切,不在句子中间断。
2. **token 上限兜底**:仅当一个语义块超 **`chunkMaxTokens = 450`** 才二次切;因为样本单元都 <450,这条基本不触发——是保险,不是主刀。
3. **重叠**:**结构边界之间不重叠**(一个"优先级"块不该把下一块的"错误表现"漏进来);只有当一个长块被 token 二次切时,才在切点加 **~40 token** 重叠,避免断在半句。
4. **上下文头(关键增召回技巧)**:每个 chunk 的**被嵌入文本前缀其层级标题**,如
   `原地罚篮 › 优先级2 辅助手参与发力\n错误表现:…`。
   这样即便正文没重复"辅助手",查询"投篮时另一只手怎么放"也能靠标题命中——对这种层级化教研稿,召回质量提升明显。标题同时存 `section_title` 元数据。
5. 每个 chunk 继承父文档 metadata + `section_title` + `chunk_index`。

参数(进 catalog 记录以便 reindex 复现):

```
splitStrategy   = STRUCTURE_FIRST         // 有序分隔符递归,非纯 token 切
chunkMaxTokens  = 450                      // 兜底上限(样本单元均 <450,极少触发)
chunkOverlap    = 40                       // 仅长块被二次切时生效;结构边界间为 0
minChunkChars   = 120                      // 过短碎块(如孤立口诀)并入其父块
prependSectionHeader = true                // 上下文头,增召回
```

**为什么不用一刀切的 `TokenTextSplitter`**:纯 token 切会无视"优先级"块和标注段这些**有意义的边界**,可能把一个纠正建议劈成两半、或把两个错误类型塞进一块。你的整理稿是天然按块组织的,顺着它切召回最准。教科书用同一套有序分隔符也能优雅退化到段落切。

> 若将来补入"长篇原理"类语料(单节远超 450),把 `chunkMaxTokens` 上调到 600 即可,无需改策略——这是随语料调的旋钮,记在 catalog 里,reindex 可复现。

### 8.4 灌库(Embedding + Load)

```
chunks(List<Document>)
  → 批量 embedding(智谱 embedding-3 @1024;必须与检索同模型,否则向量空间对不上)
  → PgVectorStore.add(batch)   // 每批 50~100,防打爆 embedding API 配额
  → catalog 写入/更新 knowledge_document(doc_id, version, chunkCount, params, hash)
```

**幂等与版本**:

- 同 `doc_id` 重导:先 `vectorStore.delete(filter: doc_id == X)` 删旧 chunk,再 add 新版(PgVectorStore 支持按 metadata filter 删除);catalog `version++`。
- 未变化(content_hash 相同)则整篇跳过。
- 下架:`delete(filter: doc_id == X)` + catalog 标记 removed。

**事务边界**:embedding 调用是外部 IO,**不能包在 DB 事务里**(技术 §9.5:事务内不调 HTTP/LLM)。流程是"先算好向量,再短事务批量写库",catalog 与 chunk 写入尽量同批;失败可按 doc_id 重导(幂等兜底)。

### 8.5 召回(Retrieval)—— 如何调用 RAG(Modular RAG)

```
问句
 ├─(简单/复杂判定)─ 简单 → 直通道:直接 similaritySearch topK
 └─ 复杂 → Query 改写(消解指代,补上下文)
            → Multi-Query(生成 2~3 子查询,TIER_FAST)
            → 并行 similaritySearch
            → 合并去重 → Rerank(LLM 打分/交叉编码)→ topN 注入
```

Spring AI 检索(在 `rag/retrieve`,经 adapter 调):

```
SearchRequest.builder()
  .query(q).topK(k)
  .similarityThreshold(0.5)
  .filterExpression("domain == 'shooting'")   // 当前仅按域过滤;checkpoint 对齐后再加 checkpoint_id
  .build()
```

- **当前是纯语义检索**:checkpoint 未对齐(§8.1),chunk 无 `checkpoint_id`,所以**不按 checkpoint 过滤**,靠"上下文头 + 语义相似度"召回——对整理稿"优先级块"这种自带主题的结构,纯语义已经够准。
- **直通道(checkpoint 就位后启用)**:将来问句能直接映射某 checkpoint(如大屏刚提示"肘外翻",学生追问"怎么改")→ 按 `checkpoint_id` filter 直查、跳过 Multi-Query 省一轮 LLM。**现在这条通道休眠**,退化为语义检索,不阻塞。
- **注入预算(§5.2)**:STUDENT_OPEN 最多注 2 段,STUDENT_STRUCTURED 最多 4 段。
- **多域扩展**:`domain` 字段现用来分投篮/其他技术;未来"训练计划模板域"等复用同机制。

### 8.6 RAG 接进 Agent:tool 还是 advisor

两种接法,按场景选:

| 接法 | 机制 | 用在 |
|---|---|---|
| **Tool**(`SearchKnowledge`) | LLM 在 ReAct 里**自主决定**何时召回 | Skill Coach 主路——能和结论层工具混编(先看数据再查知识,技术 §4.4 双路结合) |
| **Advisor**(`QuestionAnswerAdvisor`) | 每轮**自动前置**检索注入 | 纯原理直问的轻量通道 / 二期语义缓存前置 |

**决策**:学生技术答疑走 **Tool** 方式(灵活、可混编、ReAct 友好);advisor 方式留给"明显就是查知识"的简单直问。这样 RAG 是否触发由 LLM 判断,而非每轮硬查(省成本,也避免闲聊时也去检索)。

### 8.7 Memory(Episodic / Semantic / Working)

- **Working**:Redis 会话窗口(§3.3)。
- **Episodic**:`memory_vector_store`,metadata 必带 `student_id`;检索**必带 student_id filter**(隐私,与 Hook 同源——记忆也只能是自己的)。写入策略:并非每轮都写,判据——本轮触发了 Reflection(关键场景),或 importance 打分 ≥ 阈值,且每会话上限 K 条(收敛防刷屏)。importance 存 metadata。
- **Semantic**(画像):**周批单写者**离线聚合 Episodic 生成,不在对话热路径写(技术 §6.1"天然免冲突")。阶段一可暂缓,先只做 Episodic。

---

## 9. 如何做日志审计(boot 详细设计)

### 9.1 统一的 audit_log(cloud 范围)

字段以实体 `AuditLog.java` 为准(`account_id / action / target_student_id / detail(jsonb) / created_at`)。一张表服务两类事件:

| 场景 | action | target_student_id | detail |
|---|---|---|---|
| 工具调用放行 | `TOOL_INVOKE` | 被访问学生 | `{toolName, agentType, chatSessionId, requestedStudentId}` |
| 工具越权拦截 | `TOOL_DENY` | 被越权访问的学生 | `{toolName, requestedStudentId, decision:"DENY"}` |
| 单独建档 | `STUDENT_CREATE` | 新建学生 | `{studentNo}` |
| 导入建档 | `STUDENT_AUTO_CREATE` | 新建学生 | `{studentNo, lessonId}` |

**"越权一查即现"**:`action='TOOL_DENY'` 一条 SQL 捞全部越权尝试;正常访问 `TOOL_INVOKE` 里 `target_student_id != 操作者学生` 也一目了然。

### 9.2 谁在何时写(触发点)

| 触发点 | 由谁写 | 事务 |
|---|---|---|
| 工具放行/越权 | PreToolUseHook | **独立事务 `REQUIRES_NEW`** |
| 建档 | Provisioning/Manage service | 与建档同事务 |

**为什么工具审计用 `REQUIRES_NEW`**:越权会抛 `DATA_SCOPE_DENIED` 回滚业务事务;若审计与业务同事务,**越权记录会跟着回滚掉**——正好丢掉最该留的那条。所以 Hook 写审计走新事务,业务回滚不影响它。这是审计 boot 最易埋的坑,必须写死。

**技术异常兜底**:审计写失败**不阻断业务**(除越权拦截本身),try-catch 包裹,失败记 ERROR + metrics,不上抛。

### 9.3 与多线程的咬合(技术 §9.5)

- **上下文传播**:`PermissionContext`(studentId/traceId)在 SSE 异步、Reflection 重生成、Multi-Query 并行检索等线程切换处**必须显式传递**("断了是安全漏洞")。Harness 用 `AgentInvocation` 显式带贯穿,不靠 ThreadLocal。
- **事务边界在 application**:事务内不调 LLM/HTTP;工具 handler 若写库自身短事务;LLM/embedding 调用永远在事务外。

---

## 10. Boot 装配顺序(Spring 上下文启动)

```
1. LlmGateway            GLM ChatModel + 档位表 + 预算/缓存/脱敏/降级(§5.2)
2. EmbeddingModel        embedding-3 @1024(灌库与检索共用同模型)
3. PgVectorStore ×2      knowledge_vector_store / memory_vector_store(§8.0)
4. KnowledgeSeedLoader   扫 classpath:knowledge/**,幂等灌种子(§8.1)
5. ToolRegistry          注册 ToolSpec,adapter 适配成 ToolCallback(§7.1)
6. HookChain             注册强制 PreToolUseHook + 可选 Post(§5.3)
7. AgentRegistry         注册 AgentSpec(skill_coach;curriculum 禁用占位)
8. SkillLoader           读 classpath:skills/**(§6.1)
9. PermissionArbiter     Mode 决议 + 仲裁(§5.1)
10. AgentRuntime(adapter/spring) 组装以上,暴露 execute/stream/interrupt
11. RouterService(application) 静态路由表(§4.1)
12. Chat/Curriculum Controller(web) 接 SSE(cloud-agent-api)
```

**顺序硬约束**:2 在 3/4 前(灌库要 embedding);6 在 10 前(运行时要拿到强制 Hook)。

**降级启动**:`hoopshake.agent.enabled=false`(开发机无 GLM key)时 1~12 跳过,`/api/student/chat/**` 返回 `LLM_UNAVAILABLE(50310)` 而非启动失败——缺依赖降级不阻断启动。**训练数据端点(§4)不依赖 Agent,照常可用。**

---

## 11. 端到端时序:学生问一次(把全篇串起来)

```
前端 POST /ask (SSE)
 → web 鉴权(第一道闸)+ 落 USER 消息
 → application: Mode 决议(§5.1)+ Director 路由=skill_coach(§4.1)+ 组 AgentInvocation
 → AgentRuntime.stream(§3.1):
     取 AgentSpec → 仲裁工具/档位 → 组 ChatClient(system=prompt+skill §6)
     ReAct 循环(§4.2):
       LLM→调 GetProgressTrend →[PreHook 注入 studentId 第二道闸 + 写 TOOL_INVOKE §9]→ 结果
       LLM→调 SearchKnowledge →[RAG 召回 §8.5]→ 结果
       LLM 综合 → (Reflection §4.4 命中则自评1次)
     逐 token → sink.onDelta → SSE delta
 → done:落 ASSISTANT 消息 + 回填 token 预算 + 建议追问
 → 审计第三道闸:audit_log 可查本次所有工具访问(§9)
```

---

## 12. 与现有代码的差异清单(实现时对照,本轮不改码)

| 项 | 现状 | 目标态 | 出处 |
|---|---|---|---|
| Harness 骨架 | 无 | `harness/**` + `adapter/spring`(ArchUnit) | §1/§3 |
| LLM 供应商 | pom 有 zhipuai,无代码 | GLM 档位表 + Gateway | §5.2 |
| Chat 持久层 | 无 `Chat*Repository` | 建 repo + AgentSession(Redis) | §3.3 |
| Token 预算 | 错误码 42910 无执行点 | Gateway 递减 + 拦截 | §5.2 |
| Hooks | 一句话 | 强制 PreToolUseHook | §5.3 |
| audit_log | 实体在,无 Agent 写入 | 统一字段 + REQUIRES_NEW | §9 |
| `chat_message` | 无元数据列 | 加 `detail` jsonb(routedAgent 等) | §4.1 |
| RAG 存储 | `CheckpointKnowledge` 实体 @Transient embedding | ✅ PgVectorStore 自管表 + 轻量 catalog(方案 A) | §8.0 |
| RAG 导入/切分/灌库 | 无 | seed loader + admin API(异步)+ 结构优先切分/幂等灌库 | §8.1~8.4 |
| RAG 召回 | 无 | Modular RAG,tool 接入;当前纯语义(checkpoint 未对齐) | §8.5/8.6 |
| Memory | `EpisodicMemory` 实体 | ✅ 仅 Episodic;Semantic 暂缓 | §8.7 |

**开放问题结论(本轮已定,可据此写代码)**:
1. ✅ RAG 存储:方案 A(VectorStore 自管 + 轻量 catalog,废弃两实体 chunk 职责)。
2. ✅ RAG 导入:`/api/admin/knowledge/**`(ADMIN),**异步**灌库。
3. ✅ Memory:阶段一仅 Episodic,Semantic 画像暂缓。
4. ✅ 切分:结构优先(优先级块/技术小节为语义单元),`chunkMaxTokens=450` 兜底、结构边界间零重叠、上下文头前缀(§8.3,依据你的教科书 + 整理稿两份样本)。
5. ✅ 知识暂按普通文本导入,`checkpoint_id` 留空,召回纯语义;将来算法侧就位后 reindex 回填。
6. ⏸ GLM 三档型号(flash/air/plus)先占位,待你给账号实际可用型号再定(不影响其余设计)。
