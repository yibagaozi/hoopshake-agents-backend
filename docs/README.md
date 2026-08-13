# HOOPSHAKE 云端 Agent 设计文档

`v1.1 · 2026-08-13`

**范围**:`cloud` 包的多 Agent 系统,以及面向**学生端 / 教师端**两个前端的服务。回答:如何编排 Agent、如何调用 Skill / Tool、如何实现 Harness、如何做日志审计、如何调用 RAG(含**文档导入 / 切分 / 灌库**)。

**不在本轮范围**:edge、算法侧、词表权威化、ingest 包位置、MQ 通道——一律当外部已给定输入。

> **状态:设计讨论稿。本轮只更新设计文档,不产出代码。** 关键设计问题已在讨论中定稿(见文末结论),代码待你确认后单独生成发出。

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

## 开放问题结论(本轮已定,据此写代码)

1. ✅ **RAG 存储**(设计 §8.0):Spring AI `PgVectorStore` 自管表存 chunk + 一张轻量 catalog(方案 A);废弃 `CheckpointKnowledge`/`EpisodicMemory` 的 chunk 存储职责。
2. ✅ **RAG 导入入口**(API §8):`/api/admin/knowledge/**`(ADMIN)+ 启动种子加载,**异步**灌库(返回 taskId 轮询)。
3. ✅ **Memory 范围**(设计 §8.7):阶段一只做 Episodic,Semantic 画像暂缓。
4. ✅ **切分**(设计 §8.3,依据你的教科书 + 整理稿两份样本):结构优先——整理稿"优先级 N"块、教科书技术小节各为一个语义单元;`chunkMaxTokens=450` 兜底、结构边界间零重叠、上下文头前缀。
5. ✅ **知识暂按普通文本**:`checkpoint_id` 留空、召回纯语义;算法侧对齐后 reindex 回填。
6. ⏸ **GLM 型号**(设计 §5.2):`glm-4-flash/air/plus` 先占位,待你给账号实际可用型号——不影响其余设计。

---

## 落地顺序(实现时)

见 `design-audit.md` §C。要点:**第 4 步(不接 RAG 的 Skill Coach)即可先跑通学生对话**(只用结论层工具);RAG(第 5 步)灌入知识后无缝增强,不阻塞对话上线。训练数据端点(API §4)不依赖 Agent,可最先落地。
