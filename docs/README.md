# HOOPSHAKE 云端 Agent 设计文档

`v1.1 · 2026-08-13`

**范围**:`cloud` 包的多 Agent 系统,以及面向**学生端 / 教师端**两个前端的服务。回答:如何编排 Agent、如何调用 Skill / Tool、如何实现 Harness、如何做日志审计、如何调用 RAG(含**文档导入 / 切分 / 灌库**)。

**不在本轮范围**:edge、算法侧、词表权威化、ingest 包位置、MQ 通道——一律当外部已给定输入。

> **状态:设计讨论稿。本轮只更新设计文档,不产出代码。** 讨论通过后再单独生成实现发出。文末列了几个需你拍板的开放问题。

---

## 阅读顺序

| # | 文档 | 是什么 |
|---|---|---|
| 1 | [`design-audit.md`](./design-audit.md) | cloud Agent 范围的设计审计:缺口/漂移/可优化 + 落地顺序 + 本轮不处理项 |
| 2 | [`agent/agent-system-design.md`](./agent/agent-system-design.md) | **核心**:Harness 实现、Agent 编排、Skill/Tool 调用、**RAG(调用+导入/切分/灌库)**、日志审计 boot |
| 3 | [`agent/cloud-agent-api.md`](./agent/cloud-agent-api.md) | 对外契约:学生对话 §3 / 学生数据 §4 / 教师备课 §7 / 知识管理 §8 |
| 4 | [`product/student-agent-ux.md`](./product/student-agent-ux.md) | 学生端产品使用逻辑 |
| 5 | [`product/teacher-side-optimization.md`](./product/teacher-side-optimization.md) | 教师端优化 + 备课 Agent 接入 |

---

## 六个问题各在哪答

| 你问的 | 落点 |
|---|---|
| 如何**实现 Harness** | 设计 §1(分层/ArchUnit)、§3(AgentRuntime/Spec/Session、映射 Spring AI)、§10(boot 顺序) |
| 如何**编排 Agent** | 设计 §4(规则路由、ReAct 循环、Plan-and-Execute、Reflection) |
| 如何**调用 Skill** | 设计 §6(Procedural memory、classpath 载入、整挂 vs 按需) |
| 如何**调用 Tool** | 设计 §7(ToolSpec→Spring AI 绑定、学生/教师工具集、Hook 全链路) |
| 如何**做日志审计** | 设计 §9(统一 audit_log、TOOL_INVOKE/DENY、REQUIRES_NEW、多线程) |
| 如何**调用 RAG** | 设计 §8.5/8.6(Modular RAG、tool vs advisor) |
| RAG **如何导入 / 切分 / 灌库** | 设计 §8.1(导入入口)、§8.3(切分 400/60)、§8.4(embedding+批量幂等灌库);§8.0 存储决策;API §8 管理端点 |

---

## 需你拍板的开放问题(讨论后再写代码)

1. **RAG 存储**(设计 §8.0):用 Spring AI `PgVectorStore` 自管表存 chunk + 一张轻量 catalog(方案 A),**废弃** `CheckpointKnowledge`/`EpisodicMemory` 两实体的 chunk 存储职责——是否采纳?(不采纳就得回到 JPA + 自定义 vector Type 的双写,与 pgvector 一站式初衷冲突。)
2. **RAG 导入入口**(API §8):放 `/api/admin/knowledge/**`(ADMIN 角色)+ 启动种子加载,还是你另有运维/教研入口?导入大文档同步返回还是异步 taskId?
3. **GLM 型号**(设计 §5.2):三档 `glm-4-flash / air / plus` 是否与你账号可用型号一致?若不同请给实际型号。
4. **Memory 范围**(设计 §8.7):阶段一是否只做 Episodic,Semantic 画像(周批)暂缓?
5. **切分参数**(设计 §8.3):默认 400 token / 60 overlap 是否合适,取决于你们知识语料是"短纠正练习"为主还是"长篇原理"为主。

---

## 落地顺序(实现时)

见 `design-audit.md` §C。要点:**第 4 步(不接 RAG 的 Skill Coach)即可先跑通学生对话**(只用结论层工具);RAG(第 5 步)灌入知识后无缝增强,不阻塞对话上线。训练数据端点(API §4)不依赖 Agent,可最先落地。
