# 云端 Agent 实现进度

`里程碑一 · 基本聊天 + RAG(导入/切分/灌库/召回)+ 日志审计`

本篇记录已落地的代码,与设计文档 `agent-system-design.md` 对应。**算法侧 checkpoint/逐帧等数据本里程碑一律隔离**:知识按普通文本导入、`checkpoint_id` 留空、召回纯语义;锚定训练只用于选档位,不读 action_clip。

## 已实现(里程碑一)

| 能力 | 代码 | 设计 |
|---|---|---|
| Harness 分层 + 框架隔离 | `harness/**`,Spring AI 仅在 `harness/adapter/spring/**` | §1/§3 |
| LLM 网关(档位→GLM,流式) | `harness/llm/LlmGateway` + `adapter/spring/SpringAiLlmGateway`(+ `NoopLlmGateway` 兜底) | §5.2 |
| 切分器(标题树优先) | `harness/rag/MarkdownChunker`(+ 单测 `MarkdownChunkerTest`) | §8.3 |
| 向量库读写 | `harness/rag/RagStore` + `adapter/spring/SpringAiRagStore`(+ `NoopRagStore`) | §8.0/8.4/8.5 |
| 知识导入/切分/灌库(异步) | `KnowledgeIngestWorker` + `KnowledgeAdminService(Impl)` + `KnowledgeAdminController` | §8.1~8.4 |
| RAG 召回注入对话 | `ChatServiceImpl#retrieve/buildSystemPrompt` | §8.5 |
| 学生对话(SSE) | `ChatService(Impl)` + `ChatController`(`/api/student/chat`) | §3、API §3 |
| 会话/消息持久层 | `ChatSessionRepository` / `ChatMessageRepository`(+ 建表 SQL) | A4 |
| 知识目录 catalog | `KnowledgeDocument` + `KnowledgeDocumentRepository` | §8.0 |
| 日志审计(REQUIRES_NEW) | `harness/audit/AuditService` + `AuditAction`(KNOWLEDGE_*/CHAT_ASK) | §9 |

## 下一里程碑(未做)

工具调用(学生结论层工具集 + PreToolUseHook 强制注入 studentId + TOOL_INVOKE/DENY 审计)、多 Agent 路由(Director)、Working memory(Redis 窗口)、Token 预算执行、PII 脱敏/语义缓存/降级链、Episodic memory。审计里的 `TOOL_INVOKE/TOOL_DENY` 动作已预登记。

## 开关与启用

默认 `hoopshake.agent.enabled=false`:**无 GLM key 也能启动**,`/api/student/chat/**` 与 `/api/admin/knowledge/**` 降级返回 `LLM_UNAVAILABLE(50310)`,其余接口不受影响(§10 降级启动)。

启用(需真实环境):
1. 建表:执行 `cloud/src/main/resources/db/agent_chat_rag.sql`;并 `CREATE EXTENSION vector;`
2. 打开 `application.yaml` 里注释的 `spring.ai.*` 段,配 `ZHIPU_API_KEY`;首次将 `initialize-schema` 设 `true` 建向量表(建完可关)。
3. 设 `HOOPSHAKE_AGENT_ENABLED=true`。GLM 型号(`glm-4-flash/air/plus`)为占位,按账号实际可用型号改 `hoopshake.agent.tier.*`。

## 验证情况

- `MarkdownChunkerTest` 覆盖两类真实语料(教科书散文 / 整理稿优先级块)+ 纯文本降级,**通过**。
- 全模块对真实 Spring AI 2.0.0-M4 依赖**编译通过**(本环境仅 JDK 21,项目目标 Java 25,用 `-Dmaven.compiler.release=21` 做的编译校验;运行期 LLM/pgvector 连通需真实环境验证)。
- ArchUnit 强制(禁止 adapter 外 import Spring AI)与更多集成测试留待后续。

## 关键实现取舍

- **框架隔离**:`AgentRuntime` 尚未抽象成完整接口,但 Spring AI 类型已收敛在 `adapter/spring` 两个类(`SpringAiLlmGateway` / `SpringAiRagStore`),业务层只认 `LlmGateway`/`RagStore`。换框架/版本只改这两处。
- **灌库幂等**:每 chunk 显式 UUID id 存 catalog `chunk_ids`,重导/reindex 先按 id 精确删旧再灌新;内容 hash 未变则跳过。
- **异步导入**:独立 `KnowledgeIngestWorker`(规避 `@Async` 自调用),catalog 行先独立提交再触发,状态 PROCESSING→DONE/FAILED,轮询 `GET /documents/{docId}`。
- **审计独立事务**:`REQUIRES_NEW`,越权/失败回滚不带走审计记录;审计写失败不阻断业务。
- **SSE 中断**:每会话一个 `ActiveRun` 持有取消句柄;中断取消底层流并收尾发 `done{interrupted}`,`AtomicBoolean` 防重复收尾。
