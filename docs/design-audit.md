# HOOPSHAKE 设计审计与优化清单

`v1.0 · 2026-08-13 · 对照对象:仓库 HEAD(98f6716)× Cloud API v1.6 × Edge Console API v1.5 × 教师端业务逻辑 v2 × 技术参考手册 v1.0`

本清单是补全多 Agent 系统设计之前的一次全量对照结果。按严重度分组:标 🔴 的是**正确性/阻断问题**(会导致运行期出错或数据错乱),标 🟡 的是**文档与代码漂移**(文档写的和代码跑的不一致,会误导对接方),标 🟢 的是**架构缺口/可优化项**。

每条给出:现象 → 根因 → 影响 → 建议。凡能定位到代码的都带 `文件:行`。

---

## 0. 结论速览

| # | 级别 | 一句话 | 归属文档 |
|---|---|---|---|
| A1 | 🔴 | 词表三处不一致,文档示例建课直接被拒 | Cloud §12(已记录,未修) |
| A2 | 🔴 | `contracts.ingest` 包不存在,v1.6 §10.0 的"已迁移"是空头支票 | Cloud §10.0 |
| A3 | 🔴 | `lesson.class_code` 实体仍是 `VARCHAR(64)`,与 v1.6 定的 32 冲突 | Cloud §5.1 / 附录 D |
| A4 | 🔴 | MQ 入库通道(§10.7)在 pom 里没有 amqp starter,通道不存在 | Cloud §10.7 |
| B1 | 🟡 | `audit_log` 有两套字段定义(教师文档 vs 实体),且无法同时承载建档审计与工具审计 | 教师端 §1 / 技术 §8.1 |
| B2 | 🟡 | LLM 降级链 `Qwen→GLM` 无法成立:只引了 `spring-ai-starter-model-zhipuai`,没有 dashscope/Qwen | 技术 §4.3/§5.4 |
| B3 | 🟡 | Spring AI Alibaba(SAA)"原生多智能体"是选型基础,但 pom 里没有 SAA 依赖 | 技术 §2.1 |
| B4 | 🟡 | `enrollment` vs `lesson_enrollment` 表名在文档间打架 | 教师端 §1 vs Cloud §5.7 |
| B5 | 🟡 | ingest 端点命名漂移:`/action-clips`(代码)vs `/session-output`(文档 §10.2) | Cloud §10.2 |
| C1 | 🟢 | 多 Agent 系统全部只有概念、无 API 契约、无 Harness 代码骨架 | 技术 §4/§5 |
| C2 | 🟢 | `PermissionMode` 与 JWT 角色的映射规则缺失(STUDENT_OPEN vs STUDENT_STRUCTURED 如何选) | 技术 §5.2 |
| C3 | 🟢 | Token 预算有错误码(42910)无执行点 | ErrorCode:41 |
| C4 | 🟢 | RAG / Memory 有实体无管线:检索、写入、embedding 生成均未设计 | 技术 §6 |
| C5 | 🟢 | 学生端产品使用逻辑(§3 对话 / §4 数据)只有 DTO,无端点、无 service、无流程 | Cloud(缺章)|
| C6 | 🟢 | Hooks(数据隔离第二道防线)只有一句话,无触发点、无落库格式 | 技术 §5.3 |

---

## A. 正确性 / 阻断问题(🔴)

### A1 词表三处不一致 —— 文档自带的建课示例跑不通

**现象**:用 Cloud API §5.1 的示例请求建课,返回 `40000 参数校验失败 —— 包含非法训练动作`。

**根因**:同一套"动作 / 检查点"词表在系统里有三份且互相矛盾:

| 来源 | 动作 id | 检查点 id |
|---|---|---|
| Cloud 文档 §12.1 | `jump_shot` `layup` `drive` `free_throw` | `elbow_alignment` … |
| `LessonServiceImpl`(硬编码) | `shot` `layup` `dribble` | `stance` `hand` `balance` |
| `ActionClip` 实体注释 | `free_throw/jump_shot/layup` | — |
| 技术参考 §6.3 | — | `elbow_under_ball` |
| Edge 文档 §7.2 示例 | `jump_shot` | `elbow_alignment` `knee_valgus` |

定位:`cloud/src/main/java/com/cnsportiot/cloud/service/impl/LessonServiceImpl.java:52-53`。

**影响**:
1. 文档示例不可用(信任崩塌)。
2. 前端拿不到权威词表 → 各自写死 → 大屏漏渲染 `drive`/`free_throw`。
3. **对 Agent 系统是致命的**:RAG 知识库按 `checkpoint_id` 挂接(技术 §6.3),Skill Coach 的工具按 `checkpoint_id` 归因。词表不统一,`instant_feedback.checkpoint_id`(边缘写入)与 `checkpoint_knowledge.checkpoint_id`(RAG)对不上,**召回恒为空**。

**建议**(即 Cloud §12 待办,提前到 Agent 之前做,因为 Agent 依赖它):
1. 落 `contracts/src/main/resources/vocabulary.json` 为**唯一权威**(放 contracts 而非 cloud,因为 edge/算法/RAG 三方共享,见技术 §6.3)。
2. `MetaService` 读它;`LessonServiceImpl` 两个常量改为从它加载,删硬编码。
3. RAG 知识库导入时校验 `checkpoint_id ∈ vocabulary`,不匹配拒绝入库(避免建出永远召不回的知识)。

词表结构建议见 `docs/agent/agent-system-design.md` §7.3。

---

### A2 `contracts.ingest` 包不存在 —— v1.6 §10.0 的迁移是空头支票

**现象**:Cloud v1.6 §10.0 用**完成时**写"§10 的全部请求/应答 DTO **已从** `cloud.dto` **迁至** `contracts.ingest`",并列出 `contracts/.../ingest/IngestRequests.java` 等文件。

**根因**:`contracts/src/main/java/com/cnsportiot/contracts/` 下只有 `error` / `common` / `enums` 三个包,**没有 `ingest`**。DTO 仍在 `cloud/src/main/java/com/cnsportiot/cloud/dto/request/IngestRequests.java` 与 `.../dto/response/IngestDtos.java`。

**影响**:v1.6 §10.0 论证的"edge 不该再自己维护一份逐字对齐的副本"这一好处**尚未兑现**。edge 侧当前的 ingest DTO 与 cloud 端各写各的,云端改字段名 edge 照常编译、线上出现"某字段永远 null"的风险仍然存在——这正是 v1.6 想消除的。

**建议**:把这条从"文档描述的既成事实"降级为"待办项",纳入 Agent 之前的地基工作。迁移时 `contracts` 需引入 `jakarta.validation-api`(only API,不含实现),校验注解一并迁移。

---

### A3 `lesson.class_code` 列宽与 v1.6 决定冲突

**现象**:v1.6 §5.1 明确"以 **32** 为准",附录 D 的建表脚本 `ADD COLUMN class_code VARCHAR(32)`;但实体 `Lesson.java:48` 仍是 `@Column(name="class_code", length=64)`。

**影响**:JPA 建表(ddl-auto)会生成 `VARCHAR(64)`,与 v1.6 附录 D 的 SQL 迁移目标不一致;两条路径(实体 DDL vs 手工 SQL)冲突。请求 DTO 的 `@Size(max=32)` 会先挡下,所以不产生运行期错误,但**建表事实与文档决定相反**,后续以谁为准会反复扯皮。

**建议**:`Lesson.java:48` 改 `length = 32`,与 DTO 和附录 D 对齐。

---

### A4 MQ 入库通道在依赖层不存在

**现象**:Cloud v1.6 §10.7 新增"MQ 入库通道",详列 exchange `hoopshake.ingest`、routing key、DLX/DLQ;技术参考 §2.1/§3.2 也把 RabbitMQ 列为消息队列选型。

**根因**:`cloud/pom.xml` **没有** `spring-boot-starter-amqp`;代码里无任何 `@RabbitListener` / `RabbitTemplate`。全仓 grep `amqp|rabbit` 零命中(除文档)。

**影响**:§10.7 描述的整条通道(含"幂等在 `ingest_event` 台账里认领,发生在业务写入之前"这一并发保证)**当前不存在**。任何按 §10.7 对接的批处理/CV 侧都会失败。

**建议**:两选一并在文档标清:
- (a) 一期只走 HTTP ingest(edge spool 已经是 HTTP),把 §10.7 标 🚧 未实现;
- (b) 真要上 MQ,补 amqp starter + 监听器 + DLX 声明,且监听器与 HTTP 走**同一个 `IngestService` 方法**(§10.7 已正确要求这点)。
推荐 (a):课后汇总(异步)可先用 Spring `@Async` + 有界线程池实现(技术 §9.5 已提"有界并发"),不必一期就引 MQ。

---

## B. 文档与代码漂移(🟡)

### B1 `audit_log` 两套字段,且无法同时承载两类审计

**现象**:三处对 `audit_log` 的描述不一致:

| 来源 | 列 |
|---|---|
| 教师端业务逻辑 §1 | `operator_account_id`, `action`, `target_type`, `target_id`, `created_at` |
| 实体 `AuditLog.java` | `account_id`, `action`, `target_student_id`, `detail`(jsonb) |
| 技术参考 §8.1 | "工具调用留痕,越权可查" |

**根因**:audit_log 要同时服务两类事件——(a) 教师建档等**敏感业务操作**(`STUDENT_AUTO_CREATE`/`STUDENT_CREATE`),(b) Agent **工具调用与越权拦截**(技术 §5.3 Hooks)。实体是按 (b) 设计的(`target_student_id` 专列),教师文档是按 (a) 描述的(泛化 `target_type/target_id`)。两者都不完整:(b) 的 `target_student_id` 装不下"目标是一节课"的建档审计;(a) 的泛化列又丢了工具审计最关心的"被访问的 studentId"。

**影响**:建档审计和工具审计各写各的字段,查询口径撕裂;"越权一查即现"(技术 §8.1 承诺)在字段层面无法统一实现。

**建议**(在 Agent 审计 boot 里统一,见 `docs/agent/agent-system-design.md` §8):
- 保留 `account_id`(操作者)、`action`(动词枚举)、`target_student_id`(数据主体,工具审计与越权判定的核心)、`detail`(jsonb,放 `toolName`/`agentType`/`sessionId`/`requestedStudentId`/`decision` 等)、`created_at`。
- 敏感业务操作(建档)也走这张表:`action='STUDENT_CREATE'`,`target_student_id=新建学生`,`detail` 放学号等。
- 教师文档 §1 的 `target_type/target_id` 两列**删除**,统一到上面。若确需审计非学生目标(如 lesson),放 `detail.targetType/detail.targetId`,不新增列。

---

### B2 LLM 降级链 `Qwen→GLM` 在依赖层无法成立

**现象**:技术 §5.4 定义失败降级链 `Qwen→GLM→缓存→兜底`;§4.3 Director "用 TIER_FAST(qwen-turbo 档)";§4.6 Family Coach "Generator=Qwen 系,Evaluator=GLM 系"。

**根因**:`cloud/pom.xml` 只有 `spring-ai-starter-model-zhipuai`(GLM),**没有** dashscope/Qwen 的 starter。所以"主用 Qwen"的整套设定当前一个都跑不了。

**影响**:Director 的意图分类、Skill Coach 主力生成、Family Coach 的 Generator 全部落在"不存在的供应商"上;真跑起来只有 GLM 一家,降级链退化为"GLM→缓存→兜底"。

**建议**:二选一并写清:
- (a) 一期就 GLM 单供应商:把档位映射改为全 GLM(`glm-4-flash`=FAST,`glm-4-air`=STANDARD,`glm-4-plus`=ADVANCED),降级链 `GLM-primary→GLM-fallback→缓存→兜底`;Family Coach 的"异构供应商双评"标为二期(它本就【预留】)。
- (b) 真要异构:补 `spring-ai-alibaba-starter`(dashscope),但注意 M 版可用性。
推荐 (a)。LLM Gateway 的档位表见 `docs/agent/agent-system-design.md` §5。

---

### B3 SAA 是选型基础但未进 pom

**现象**:技术 §2.1 "Agent 编排 = Spring AI Alibaba 2.0.0-M1.1,原生多智能体模式(routing/supervisor/handoff),经 Harness 适配层隔离";§5.1 "adapter/saa 是唯一允许 import SAA 类型的包(ArchUnit 强制)"。

**根因**:pom 无 SAA 依赖;`adapter/saa` 包不存在;无 ArchUnit。

**影响**:整个 Harness 隔离论证(§5)目前是纸面架构。

**建议**:Harness 的价值不依赖 SAA 是否 M1.1——**先按 Spring AI 原生 `ChatClient` + `tools` + `advisors` 落地 AgentRuntime 抽象**,把 SAA 的 supervisor/handoff 当作 `adapter/saa` 里可替换的实现。这样即便 SAA 版本未定,Harness 与业务层照样能建起来。详见 §5 的分层与 boot 顺序。

---

### B4 `enrollment` vs `lesson_enrollment` 表名打架

**现象**:教师端 §1 数据表写 `enrollment`,PK(`lesson_id`,`student_id`);Cloud v1.6 §5.7/附录 D 与实体都是 `lesson_enrollment`。

**根因**:实体 `LessonEnrollment.java:10` `@Table(name="lesson_enrollment")`,唯一约束 `uk_lesson_student(lesson_id, student_id)`。以代码为准是 `lesson_enrollment`。

**建议**:教师端业务逻辑文档 §1、§5.6 的 SQL(`enrollment e join ...`)统一改 `lesson_enrollment`。见 `docs/product/teacher-side-optimization.md`。

---

### B5 ingest 端点命名漂移

**现象**:代码 `IngestController` 暴露 `POST /api/ingest/action-clips`(`IngestController.java:27`);Cloud 文档 §10.2 写 `POST /api/ingest/session-output`;附录 C(Edge)也写 `session-output`。

**影响**:CV/算法侧按文档发 `session-output` 会 404。

**建议**:统一为一个。建议保留 `/action-clips`(语义更准:它写的是 action_clip 表),文档 §10.2 与 Edge 附录 C 同步改名;或反之。任选其一但必须一致。

---

## C. 架构缺口 / 可优化项(🟢)—— 多 Agent 系统

> 以下是本次要补全的主体。此处只列"缺什么、为什么要补",完整设计在 `docs/agent/` 两篇。

### C1 多 Agent 系统无 API 契约、无 Harness 骨架
技术 §4/§5 有组件总览与哲学,但没有:Agent 的调用入口(REST/SSE 端点)、AgentRuntime 接口签名、AgentSpec/ToolSpec/HookSpec 的字段、boot 装配顺序。→ 补:`docs/agent/agent-system-design.md`(设计+boot)、`docs/agent/cloud-agent-api.md`(端点契约)。

### C2 `PermissionMode` 与角色的映射缺失
§5.2 定义了四种 Mode 的白名单/预算/档位上限,但**没说一个进来的 JWT(role=STUDENT)如何被判成 STUDENT_OPEN 还是 STUDENT_STRUCTURED**。这是仲裁的入口,缺了整套权限模式无从启动。→ 补:§5.2 增加"Mode 决议规则"(按会话是否锚定 lessonId / 入口路径决定)。

### C3 Token 预算有码无点
`ErrorCode.TOKEN_BUDGET_EXCEEDED(42910)` 已定义(`ErrorCode.java:41`),但无任何计数与拦截。→ 补:LLM Gateway 在会话维度累计 token,超 Mode 预算抛 42910(§5.4)。

### C4 RAG / Memory 有实体无管线
`CheckpointKnowledge`/`EpisodicMemory` 实体在,`@Transient float[] embedding` 说明走 VectorStore;但**没有**:知识导入流程、embedding 生成时机、检索(Modular RAG)编排、RAG↔Memory 边界的代码落点。→ 补:§6、§7。

### C5 学生端产品逻辑缺失
`ChatRequests`/`ChatDtos`(含 SSE meta/delta/tool/done 事件)在,但无 controller/service/repo(`Chat*Repository` 不存在),无对话生命周期、无与训练数据(§4)的联动流程。→ 补:`docs/agent/cloud-agent-api.md` §3/§4 + `docs/product/student-agent-ux.md`。

### C6 Hooks 只有一句话
§5.3 说 PreToolUseHook 强制注入 studentId、越权抛异常并写 audit_log,但没有触发点、参数、audit 落库格式、PostToolUse 用途。→ 补:§5.3 展开 + §8 审计 boot。

### C7(优化)`chat_session.agent_type` 默认值先于路由存在
实体默认 `agent_type="skill_coach"`(`ChatSession.java`)。多 Agent 上线后,同一会话可能被 Director 路由到不同 Agent。建议:`agent_type` 记录**会话主 Agent**,单条消息实际命中的 Agent 记 `chat_message.detail.routedAgent`(需给 ChatMessage 加 jsonb 元数据列),否则事后无法复盘"这句是谁答的"。见 §3。

### C8(优化)`episodic_memory` 写入触发点未定
四层记忆(技术 §6.1)说"实时写只进 Episodic",但没定义**什么样的对话轮次值得写成一条 episodic memory**(全写会污染召回,全不写等于没记忆)。→ 补:§6.4 写入策略(按 importance 阈值 + 每会话收敛)。

---

## D. 小的可优化点(🟢,非阻断)

- **D1** `hoopshake.student.initial-password` 在 `application.yaml` 末尾是空值且无换行(`initial-password:` 后直接 EOF)。v1.6 §5.7 说"留空则用学号本身"——需确认 `StudentProperties` 对空串走的是"用学号"分支而非"空密码"。建议显式默认 `${HOOPSHAKE_STUDENT_INIT_PWD:}` 并在 provisioning 里判空。
- **D2** `@Enumerated(STRING)` 列宽不统一(8/16/24 混用),v1.6 附录 D 已建议统一放宽到 32。`ChatSession.agent_type`(32)、`ChatMessage.role`(16)也在此列,统一时一并处理。
- **D3** `SessionStatus` 用 `ordinal()` 判前进(v1.6 附录 A),而 Agent 侧工具会读 `training_session.status` 判"报告是否 ready"。建议 Agent 侧只用 `>= SCORED` 之类的**语义比较**(封装成方法),不要在 Agent 代码里再写一遍 ordinal,避免第二处依赖声明顺序。
- **D4** `chat_message.token_usage` 只在 ASSISTANT 有值(DTO 注释),但 Token 预算(§5.4)需要累计**整会话**含 system/RAG 注入的 token。建议区分 `token_usage`(可见回答)与 `billed_tokens`(计入预算的总量,含 prompt),后者进 detail 或单列。

---

## E. 建议的落地顺序(地基先行)

多 Agent 系统依赖一批地基,顺序不能反:

```
1. 词表权威化(A1)         ── RAG/工具/校验全依赖它
2. contracts.ingest 迁移(A2)+ 列宽/命名对齐(A3/A4/B4/B5)
3. audit_log 统一(B1)     ── Hooks 的落点
4. Harness 骨架(§5):AgentRuntime + AgentSpec/ToolSpec/HookSpec + PermissionMode 决议 + LLM Gateway(GLM 单供应商,B2)
5. 工具层(§7)+ Hooks(§5.3)+ 审计 boot(§8)
6. RAG/Memory 管线(§6)
7. Coach Director 路由(§4)+ Skill Coach(学生端主力)
8. 学生端对话/数据 API(cloud-agent-api §3/§4)落地
9. 教师端 Curriculum(Plan-and-Execute)—— 二期
```

第 1~3 步是纯地基(修 bug + 对齐),不引入 LLM;第 4 步起进入 Agent 本体。**在第 1 步完成前不要接 RAG**,否则会调试一个"知识库明明有内容却召不回"的幽灵问题(根因就是 A1)。
