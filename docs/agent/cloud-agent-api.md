# HOOPSHAKE Cloud Agent API v1.1

`2026-08-13 · cloud 包 · 面向学生/教师前端 + 知识管理端`

多 Agent 系统的**对外契约**:学生对话(§3)、学生训练数据(§4)、教师备课 Curriculum(§7)、知识库管理(§8,运维/教研用)。内部实现(编排/RAG/工具/Harness)见 `agent-system-design.md`。

约定:通用响应包 `ApiResponse{code,message,data}`、分页、错误码沿用现有 `contracts.error.ErrorCode`。DTO 引用现有 `ChatRequests`/`ChatDtos`/`StudentDataDtos`,不重复字段,只标行为。

**范围**:cloud 包 Agent 与前端服务。不含 edge / ingest 通道 / 词表——训练数据由结论层表(`action_clip`/`session_aggregate`/`instant_feedback`)提供,本篇只读它们,不关心谁写入。

> 状态:**设计讨论稿,本轮不产出代码。**

---

## §3 学生对话 `/api/student/chat`

鉴权 `@RequireRole(STUDENT)`;数据主体固定 token 中的 `studentId`(不接受请求体传 studentId)。Mode 由是否锚定训练决议(设计 §5.1):锚定 → `STUDENT_STRUCTURED`,否则 `STUDENT_OPEN`。

### 3.1 新建会话 · `POST /api/student/chat/sessions`
请求 `CreateChatSessionRequest{title?, trainingSessionId?}`。
- `title` 缺省 → 服务端按首问生成。
- `trainingSessionId` 给了则校验归属当前学生(该 session 的 clip 里有本人),否则 `40301`;锚定后默认 `STUDENT_STRUCTURED`。
响应 `ChatSessionResponse{sessionId,title,createdAt,updatedAt}`。

### 3.2 会话列表 · `GET /api/student/chat/sessions`
`chat_session where student_id=:current and deleted=false order by updated_at desc`,分页。

### 3.3 会话消息 · `GET /api/student/chat/sessions/{sessionId}/messages`
归属校验 → `chat_message where chat_session_id=? order by created_at`,分页正序。`ChatMessageResponse{...,tokenUsage(仅 ASSISTANT)}`。

### 3.4 提问(SSE)· `POST /api/student/chat/sessions/{sessionId}/ask`
**学生端主路。** 请求 `ChatAskRequest{content, trainingSessionId?}`,`Accept: text/event-stream`。归属校验 → Mode 决议 → 落 USER 消息 → 组 `AgentInvocation` → `AgentRuntime.stream(sink)`。SSE 事件(对齐 `ChatDtos`):

| event | 负载 | 时机 | 可丢 |
|---|---|---|---|
| `meta` | `ChatMetaEvent{messageId,sessionId}` | 落 ASSISTANT 空壳后 | ✘ |
| `tool` | `ChatToolEvent{name,status,label}` | 工具进入/完成;`label` 中文短句 | ✔ |
| `delta` | `ChatDeltaEvent{text}` | LLM 逐 token | ✔(整体不可丢) |
| `done` | `ChatDoneEvent{messageId,finishReason,tokenUsage,suggestions}` | 收尾 | ✘ |
| `error` | `ErrorInfo{code,message}` | 流内异常(不走 REST 处理器) | ✘ |

- `finishReason`:`stop`/`interrupted`(§3.6)/`length`。
- 心跳 `: ping`/15s;断线由客户端重连(不做断点续传,已生成部分已落库)。
- Token 超预算 → 流内 `error` `TOKEN_BUDGET_EXCEEDED(42910)`,已生成保留。

### 3.5 会话改名 · `PATCH /api/student/chat/sessions/{sessionId}`
`{title}`(≤64),归属校验 → 改 title。

### 3.6 中断 · `POST /api/student/chat/sessions/{sessionId}/interrupt`
归属校验 → `AgentRuntime.interrupt(runId)`。无进行中生成 → 幂等成功。触发 §3.4 `done{finishReason:"interrupted"}`。

### 3.7 删除会话 · `DELETE /api/student/chat/sessions/{sessionId}`
归属校验 → 软删(`deleted=true`)。幂等。不物理删 `chat_message`(审计/记忆可能引用)。

### §3 错误码
`40301` 非本人/锚定越权;`40400` 会话不存在;`42910` 预算耗尽;`50310` AI 不可用(降级链兜底后仍失败)。

---

## §4 学生训练数据 `/api/student/training`

鉴权 `@RequireRole(STUDENT)`,数据主体固定 token `studentId`。这些是**对话的数据底座**(学生工具集读的就是这几张表,设计 §7.2),也是"我的训练情况"页数据源。DTO 全在 `StudentDataDtos`。**不依赖 Agent**——LLM 不可用时照常可用。

| # | 端点 | 响应 | 读表 |
|---|---|---|---|
| 4.1 | `GET /training/overview` | `TrainingOverviewResponse` | 跨 session 聚合(与教师 §6.5 共用 service) |
| 4.2 | `GET /training/sessions` | `SessionBriefResponse`(分页) | `training_session`+clip 计数 |
| 4.3 | `GET /training/sessions/{sessionId}` | `SessionDetailResponse` | +`session_aggregate` |
| 4.4 | `GET /training/sessions/{sessionId}/clips` | `ActionClipResponse`(列表) | `action_clip` |
| 4.5 | `GET /training/clips/{clipId}` | `ActionClipResponse`(完整,含 phases/motionRange/`motionDataUrl`) | `action_clip` + MinIO 预签名 |
| 4.6 | `GET /training/sessions/{sessionId}/feedback` | `InstantFeedbackResponse`(分页) | `instant_feedback` |
| 4.7 | `GET /training/trend?actionType=&metric=` | `ProgressTrendResponse` | 跨 session 窗口聚合 |

要点:
- **归属**:所有 session/clip 必须属当前学生(clip.student_id==current),否则 `40301`。§4.1 与教师 §6.5 **共用同一 service**,差别只在 studentId 来源。
- **4.5 `motionDataUrl`**:逐帧在 MinIO,返回短时预签名 URL,**LLM 工具永不读它**(设计 §7.2 红线),仅供 3D 回放前端。
- `made_rate` 全 NULL 返回 null 不填 0;`sessionStatus < SCORED` 原样透出不报错(用语义比较,不用 ordinal)。

---

## §7 教师备课 Curriculum `/api/teacher/curriculum`(阶段二)

鉴权 `@RequireRole(TEACHER)`,`TEACHER_LESSON` Mode,**Plan-and-Execute**(设计 §4.3)。**阶段一全部 `50100`**,此处定契约供二期落地。

| # | 端点 | 行为 |
|---|---|---|
| 7.1 | `POST /curriculum/plans` | `{lessonId?,topic,gradeBand?,constraints?}` → 创建 async_tasks(`PLANNING`),异步出大纲 → `{taskId,status}` |
| 7.2 | `GET /curriculum/plans/{taskId}` | `{taskId,status,plan?,result?}`;status∈`PLANNING/PLAN_READY/EXECUTING/DONE/FAILED` |
| 7.3 | `PUT /curriculum/plans/{taskId}/plan` | `{plan}` 教师改后大纲 → 写检查点,`PLAN_READY→EXECUTING`(ReAct 做不到的中断修改) |
| 7.4 | `GET /curriculum/plans/{taskId}/document` | `DONE` 时返回 Word/PPT 下载引用;未完成 `40910` |
| 7.5 | `GET /curriculum/plans/{taskId}/stream` | 执行阶段进度 SSE(复用 §3.4 的 `tool/delta/done`) |

联动:`GetClassSummary` 工具复用教师端 §8.1 口径;教案产物可回填 lesson 的 `action_types`/`enabled_checkpoints`(经教师端 §5.4)。错误码:阶段一恒 `50100`;二期 `40301`/`40910`/`42910`。

---

## §8 知识库管理 `/api/admin/knowledge`(运维/教研)

RAG 知识的**导入 / 切分 / 灌库 / 下架**入口。鉴权 `@RequireRole(ADMIN)`。**不属学生/教师两个前端**——它们只消费召回,不导入。机制详见设计 §8.1~8.4。

| # | 端点 | 行为 |
|---|---|---|
| 8.1 | `POST /admin/knowledge/documents` | 上传/登记一篇文档(multipart 或 `{source,domain,content}`)→ **异步**切分灌库 → 返回 `{taskId}` |
| 8.1a | `GET /admin/knowledge/tasks/{taskId}` | 轮询导入进度:`{status,docId,chunks}`,`status∈PROCESSING/DONE/FAILED` |
| 8.2 | `GET /admin/knowledge/documents` | 列已导入源文档(读 catalog):`{docId,source,domain,version,chunks,importedAt}`,分页 |
| 8.3 | `GET /admin/knowledge/documents/{docId}` | 某源文档详情 + chunk 数 + 切分参数 |
| 8.4 | `DELETE /admin/knowledge/documents/{docId}` | 下架:删该 doc 全部 chunk(按 metadata filter)+ catalog 标 removed。幂等 |
| 8.5 | `POST /admin/knowledge/documents/{docId}/reindex` | 用当前切分参数/embedding 模型重切重灌(改参数、换模型、或将来补 checkpoint 对齐时) |
| 8.6 | `POST /admin/knowledge/search`(调试) | `{query,domain?,topK?}` → 返回召回片段 + 相似度,供教研验证"这条知识能不能被召回" |

要点:
- **异步导入**(已定):大文档 embedding 慢,`POST` 立即返回 `taskId`,后台切分灌库,`8.1a` 轮询。与教师端 Curriculum 复用同一 async_tasks 状态机(设计 §8.1)。
- **checkpoint 暂不带**:算法侧未对齐,导入一律按普通文本,`checkpointId` 留空,召回纯语义(设计 §8.1/§8.5);将来 reindex 回填。
- **幂等**:同 `docId` 重导先删旧 chunk 再灌新版(设计 §8.4);未变化(content_hash 同)跳过。
- **8.6 调试检索**是教研自查工具:导入后立刻验证召回质量,不必等学生对话踩坑。**强烈建议保留**——RAG 最常见故障是"知识在库里但召不回",这个端点让它当场暴露。

### §8 错误码
`40000` 文档格式无法解析 / 参数缺失;`40400` docId 不存在;`50000` 灌库失败(embedding 或写库异常,可按 docId 重导)。

---

## 附:端点与内部能力对应

| 本篇 | 内部(agent-system-design) | 说明 |
|---|---|---|
| §3.4 ask 编排 | §3 Harness + §4.2 ReAct + §7 工具 + §8 RAG | 一次问答的完整链路见设计 §11 |
| §3/§4 数据主体固定 studentId | §5.3 Hook 强制注入 | 三道数据闸 |
| §4 读结论层 | §7.2 学生工具集 | Agent 与前端读同一批结论表 |
| §8 知识管理 | §8.1~8.4 导入/切分/灌库 | admin 灌,前端只召回 |
| §7 Curriculum | §4.3 P&E + §7.3 教师工具 | 二期 |

**上线次序建议**:§4(训练数据,不依赖 Agent)可最先;§3 对话随 Harness + Skill Coach 落地;§3.4 里的 RAG 召回随 §8 灌入知识后自然增强(未灌知识时 Skill Coach 降级为只用结论层工具,不硬接空召回);§7 Curriculum 二期。
