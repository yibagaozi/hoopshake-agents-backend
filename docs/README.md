# HOOPSHAKE 文档索引

`2026-08-13`

本目录补全并优化 HOOPSHAKE 云端多 Agent 系统的设计文档。上游文档(Cloud API v1.6、Edge Console API v1.5、教师端业务逻辑 v2、技术参考手册 v1.0)描述了系统骨架与边缘/教师能力,但**多 Agent 系统本身只有概念、无契约与装配设计**,学生端产品逻辑亦缺失。本目录填这两块,并给出一次全量设计审计。

**本轮不含代码实现**(按需求"暂时不要实现代码");所有内容为设计与文档,实现前请先过审计的落地顺序。

---

## 阅读顺序

| # | 文档 | 是什么 | 先读它当…… |
|---|---|---|---|
| 1 | [`design-audit.md`](./design-audit.md) | 仓库 × 四份文档的全量对照:🔴 正确性问题 / 🟡 文档漂移 / 🟢 架构缺口,含落地顺序 §E | 想知道现状有哪些坑、从哪开始动手 |
| 2 | [`agent/agent-system-design.md`](./agent/agent-system-design.md) | **核心**:Harness 理念与 boot、Agent 路由、RAG 召回、Skill、Tools 调用、日志审计 boot | 要实现 Agent 系统内部 |
| 3 | [`agent/cloud-agent-api.md`](./agent/cloud-agent-api.md) | 对外契约:学生对话 §3 / 学生数据 §4 / 教师备课 §7(SSE、DTO、错误码) | 要对接前端或写 Controller |
| 4 | [`product/student-agent-ux.md`](./product/student-agent-ux.md) | 学生端产品使用逻辑:激活闸 → 聊天回路 → 训练数据 → 空态/降级 | 要设计学生端体验 |
| 5 | [`product/teacher-side-optimization.md`](./product/teacher-side-optimization.md) | 教师端优化:必改冲突项 + 体验优化 + 备课 Agent 接入 | 要改教师端 |

---

## 本轮完成了什么(对照需求)

| 需求 | 落点 |
|---|---|
| 补全多 Agent 系统:**Agent 路由** | `agent-system-design.md` §4(阶段一规则路由 + 二期意图分类 + 三个边界) |
| **RAG 召回** | §6(Modular RAG 编排、直通道、embedding 落点、Episodic 写入策略) |
| **Skill** | §7.4(Procedural memory / Skill 文件 vs RAG 的区别) |
| **Tools 调用** | §7.1–7.5(ToolSpec、学生/教师工具集、调用全链路) |
| **Harness 理念 + boot 详细设计** | §0–3(三条不变量、分层、AgentRuntime/Spec)、§5(PermissionMode 决议、LLM Gateway、Hooks)、§9(boot 装配顺序) |
| **日志审计 boot 详细设计** | §8(统一 audit_log、触发点、独立事务、多线程咬合) |
| **审计现有设计的问题与可优化项** | `design-audit.md`(19 条,按严重度) |
| **完成学生端 Agent 产品使用逻辑** | `product/student-agent-ux.md` |
| **优化教师端** | `product/teacher-side-optimization.md` |
| **优化产品和技术设计文档** | 见下"对上游文档的修订清单" |

---

## 对上游文档的修订清单(需回写)

以下是本轮发现、应回写进上游文档的修订。每条都在审计里有完整论证。

**技术参考手册 v1.0 → 建议 v1.1**
- §2.1/§4.3/§5.4:LLM 供应商阶段一收敛为 **GLM 单供应商**,档位映射改 `glm-4-flash/air/plus`;Qwen/异构双评标二期(审计 B2)。
- §2.1/§5.1:SAA 未定版不阻塞——先按 Spring AI 原生落 `AgentRuntime`,SAA 作为 `adapter/saa` 可换实现(审计 B3)。
- §5.2:补 **Mode 决议规则**(按角色 + 是否锚定训练决议 STUDENT_OPEN/STRUCTURED),原文只有能力表无决议入口(审计 C2)。
- §5.3:Hooks 展开为强制 PreToolUseHook(studentId 注入/越权拦截)+ 落库格式(审计 C6)。
- §6:RAG/Memory 补管线(检索编排、embedding 写入时机、Episodic 写入策略)(审计 C4/C8)。
- §一/§3.2:RabbitMQ 一期可用 `@Async` + 有界线程池替代,MQ 通道标二期(审计 A4)。

**Cloud API v1.6 → 建议 v1.7**
- §10.0:`contracts.ingest` 迁移由"已完成"降级为"待办"(实际未迁)(审计 A2)。
- §10.7:MQ 入库通道标 🚧(pom 无 amqp)(审计 A4)。
- §10.2:ingest 端点命名统一(`/action-clips` vs `/session-output`)(审计 B5)。
- 附录 D:确认 `lesson.class_code` 实体列宽收窄到 32(审计 A3)。
- 新增 §3/§4/§7 章节 = 本目录 `cloud-agent-api.md`。

**教师端业务逻辑 v2 → 建议 v2.1**
- §1:表名 `enrollment`→`lesson_enrollment`;audit_log 字段对齐实体(审计 B1/B4)。
- §5.1/§5.4:建课词表改从权威词表加载(审计 A1)。
- §6.4:重定位为"补齐空档案"的常规动作;名单页标记空 `dominantHand`(Cloud v1.6 §6.1)。
- 详见 `product/teacher-side-optimization.md`。

---

## 实现前必读

`design-audit.md` §E 的落地顺序:**先修地基(词表权威化 → contracts.ingest 迁移/列宽命名对齐 → audit_log 统一),再建 Harness,最后接 Agent 与端点。** 在词表权威化(A1)完成前不要接 RAG——否则会调试一个"知识库有内容却召不回"的幽灵 bug,根因就是 checkpoint_id 对不上。
