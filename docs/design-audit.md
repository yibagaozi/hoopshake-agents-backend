# HOOPSHAKE 云端 Agent 设计审计

`v1.1 · 2026-08-13 · 范围:cloud 包 Agent + 学生/教师前端服务`

本轮审计**只针对 cloud 包的 Agent 部分与面向两个前端的服务**。词表、edge、ingest 包位置、MQ 通道等按当前需求**不在本轮范围**,集中列在 §D"本轮不处理"里备查,不展开。

级别:🔴 正确性/阻断 · 🟡 文档与代码漂移 · 🟢 架构缺口/可优化。每条给现象 → 根因 → 影响 → 建议,能定位代码的带 `文件:行`。

---

## 速览

| # | 级别 | 一句话 | 落点 |
|---|---|---|---|
| A1 | 🟡 | 多 Agent 系统全部只有概念,无 Harness 骨架/无 API/无 boot | 设计 §1/§3/§10 |
| A2 | 🟡 | `PermissionMode` 与角色的决议规则缺失(OPEN vs STRUCTURED 怎么选) | 设计 §5.1 |
| A3 | 🟡 | Token 预算有错误码 42910、无执行点 | 设计 §5.2 |
| A4 | 🟡 | Chat 持久层缺失(无 `Chat*Repository`,无 AgentSession) | 设计 §3.3 |
| A5 | 🟡 | Hooks(数据隔离第二道防线)只有一句话,无触发点/落库格式 | 设计 §5.3/§9 |
| A6 | 🟡 | `audit_log` 两套字段(教师文档 vs 实体),无法同承两类审计 | 设计 §9.1 |
| A7 | 🟡 | LLM 降级链 `Qwen→GLM` 在依赖层不成立(只有 zhipuai) | 设计 §5.2 |
| A8 | 🔴 | **RAG 只有实体、无管线**:导入/切分/灌库/召回全缺;且存储走 JPA 双写与选型初衷冲突 | 设计 §8 |
| A9 | 🟡 | 学生端产品逻辑缺失(§3/§4 只有 DTO,无端点/service/流程) | cloud-agent-api §3/§4 |
| A10 | 🟢 | `chat_session.agent_type` 先于路由存在,但单消息命中的 Agent 无处可记 | 设计 §4.1 |
| A11 | 🟢 | Episodic memory 写入触发点未定(全写污染召回,全不写等于没记忆) | 设计 §8.7 |

---

## A. 阻断 / 缺口

### A8 🔴 RAG 只有实体,管线整体缺失(本轮头号)

**现象**:`CheckpointKnowledge`/`EpisodicMemory` 实体在,`@Transient float[] embedding` 注释说走 VectorStore;但**没有**导入、切分、灌库、召回任何一环的代码或设计。`checkpoint_knowledge`/`episodic_memory` 也没有对应 Repository(除实体外无访问层)。

**根因**:pom 已引 `spring-ai-starter-vector-store-pgvector`(cloud/pom.xml),但从未接线;RAG 从"有依赖"到"能召回"之间的全部工程量(读文档→切分→embedding→写 pgvector→检索编排)都没做。

**影响**:Skill Coach 的"双路数据结合"(技术 §4.4)缺一路——只能读结论层工具,给不出"这个动作原理上为什么错、该怎么练"的知识性回答,产品价值打对折。

**附带问题(存储路线)**:实体注释想让"实体存元数据、embedding 走 VectorStore",但这会变成**一份内容两处存**(实体表 + 向量表),正好违背技术 §8.2"pgvector 一站式、免维护双存储一致性"的选型初衷。

**建议**(设计 §8 已完整给出):
- 存储:用 Spring AI `PgVectorStore` **自管表**作为 chunk 唯一存储(内容+metadata+embedding 三合一),另置一张轻量 catalog 表记源文档便于重导/下架;废弃两个实体的 chunk 存储职责(设计 §8.0 方案 A,**待确认**)。
- 管线:seed loader + admin API 导入(§8.1),结构优先+token 兜底切分 400/60(§8.3),批量幂等灌库(§8.4),Modular RAG 召回、以 tool 方式接进 ReAct(§8.5/8.6)。

---

### A1 🟡 多 Agent 系统无 Harness 骨架 / 无 API / 无 boot
技术 §4/§5 有组件总览与哲学,但没有 `AgentRuntime` 接口、`AgentSpec/ToolSpec/HookSpec`、boot 装配顺序、对外端点。→ 设计 §1/§3/§10 + cloud-agent-api。

### A2 🟡 PermissionMode 决议规则缺失
§5.2 给了四种 Mode 能力表,但没说一个 `role=STUDENT` 的请求如何被判成 `STUDENT_OPEN` 还是 `STUDENT_STRUCTURED`——仲裁没有入口。→ 设计 §5.1 补"按角色 + 是否锚定训练决议"。

### A3 🟡 Token 预算有码无点
`ErrorCode.TOKEN_BUDGET_EXCEEDED(42910)`(ErrorCode.java:41)已定义,无任何计数/拦截。→ 设计 §5.2 LlmGateway 会话维度递减 + 拦截,计整 prompt(不只可见回答)。

### A4 🟡 Chat 持久层缺失
`ChatSession`/`ChatMessage` 实体与 `ChatDtos`(含 SSE meta/delta/tool/done)在,但无 `Chat*Repository`、无 controller/service、无 Working memory(AgentSession)。→ 设计 §3.3 + cloud-agent-api §3。

### A5 🟡 Hooks 只有一句话
技术 §5.3 说 PreToolUseHook 注入 studentId、越权抛异常并写 audit_log,但无触发点、参数、落库格式、PostToolUse 用途。→ 设计 §5.3 展开 + §9。

### A6 🟡 audit_log 两套字段
教师端 §1 写 `operator_account_id/action/target_type/target_id`;实体是 `account_id/action/target_student_id/detail(jsonb)`,还要同承 Agent 工具审计。两者都不完整。→ 设计 §9.1 统一到实体字段,教师文档回写(见 product/teacher-side-optimization §1.2)。

### A7 🟡 LLM 降级链在依赖层不成立
技术 §5.4 定 `Qwen→GLM→缓存→兜底`;§4.3 Director "qwen-turbo";§4.6 Family Coach Generator=Qwen。但 cloud/pom.xml 只有 `spring-ai-starter-model-zhipuai`(GLM),无 Qwen/dashscope。→ 设计 §5.2 阶段一收敛 GLM 单供应商(`glm-4-flash/air/plus`),降级链 `glm-primary→降档→缓存→兜底`;异构双评二期。

### A9 🟡 学生端产品逻辑缺失
只有 DTO,无端点/service/流程,无与训练数据(§4)的联动。→ cloud-agent-api §3/§4 + product/student-agent-ux。

---

## B. 可优化(🟢)

### A10 单消息命中的 Agent 无处记录
`ChatSession.agent_type` 默认 `"skill_coach"`(实体),记的是**会话主 Agent**。多 Agent 后单条消息可能被路由到不同 Agent,但 `ChatMessage` 无元数据列,事后无法复盘"这句谁答的"。→ 给 `chat_message` 加 `detail` jsonb,记 `routedAgent`/工具轨迹摘要(设计 §4.1)。

### A11 Episodic 写入触发点未定
技术 §6.1"实时写只进 Episodic",但没定"什么样的轮次值得写成一条记忆"。全写污染召回,全不写等于没记忆。→ 设计 §8.7:触发 Reflection 或 importance≥阈值,且每会话上限 K 条。

### 其他小项
- **B1** `chat_message.token_usage` 只在 ASSISTANT 有值(DTO 注释),但预算要累计**整会话含 prompt** 的 token。→ 区分 `token_usage`(可见回答)与计入预算的总量(设计 §5.2)。
- **B2** Agent 侧读 `training_session.status` 判"报告是否 ready" 时,用**语义比较**(`>= SCORED` 封装成方法),不要在 Agent 代码里再写一遍 ordinal 比较,避免第二处依赖枚举声明顺序。
- **B3** `SessionAggregate`/`ActionClip` 的 `stats`/`score` 是 jsonb,工具读出后结构不定。→ 工具层应约定一个稳定的"结论视图 DTO",别把 raw jsonb 直接喂给 LLM(不可控、浪费 token)。

---

## C. 落地顺序(cloud Agent)

```
1. Harness 骨架        AgentRuntime + Spec + adapter/spring(ArchUnit)+ LlmGateway(GLM)
2. PermissionMode 决议 + Hooks + 审计 boot(A2/A5/A6)     ── 数据隔离三道闸
3. Chat 持久层 + AgentSession(A4)
4. 工具层(学生工具集,只读结论层)+ Skill Coach(ReAct)
5. RAG 管线(A8):存储决策 → 导入/切分/灌库 → 召回 → 以 tool 接入
6. 学生端 §3/§4 端点落地(A9)
7.(二期)Curriculum P&E + 教师工具 + Semantic 画像
```

**第 4 步(不接 RAG 的 Skill Coach)即可先跑通学生对话**——只用结论层工具就能回答"我最近怎么样";RAG(第 5 步)灌入知识后无缝增强,不阻塞对话上线。这样 RAG 的工程量不卡住学生端的最小可用。

---

## D. 本轮不处理(已知,另行跟进)

以下在上一版审计里出现过,按当前需求**移出本轮范围**,不展开,仅备查:

- 词表三处不一致(动作/检查点 id)—— 本篇一律把 `checkpoint_id` 当外部给定标签;RAG 召回**不以词表统一为前提**(对不上就退化为无 filter 语义检索,不阻塞,设计 §8.5)。
- `contracts.ingest` 迁移是否完成、ingest 端点命名(`/action-clips` vs `/session-output`)。
- edge 侧一切(名单、CV、spool、大屏)。
- RabbitMQ / MQ 入库通道(cloud 一期课末汇总可用 `@Async` + 有界线程池,不引 MQ)。
- `lesson.class_code` 列宽、`enrollment` vs `lesson_enrollment` 表名(教师文档层面,见 product/teacher-side-optimization,不影响 Agent)。
