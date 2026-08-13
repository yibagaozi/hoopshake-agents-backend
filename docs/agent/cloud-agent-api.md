# HOOPSHAKE Cloud Agent API v1.0

`2026-08-13 · 补 Cloud API §3(学生对话)/§4(学生数据)/§7(教师备课),对齐 v1.6 编号`

本篇是多 Agent 系统的**对外契约**,补上 Cloud API v1.6 里只有 DTO、缺端点定义的三块:学生对话(§3)、学生训练数据(§4)、教师备课 Curriculum(§7)。内部实现(路由/RAG/工具/Harness)见 `agent-system-design.md`。

约定:通用响应包 `ApiResponse{code,message,data}`、分页 §1.4、错误码 §1.5,均沿用 Cloud API v1.6。DTO 引用现有 `ChatRequests`/`ChatDtos`/`StudentDataDtos`,字段不重复列,只标端点行为。

---

## §3 学生对话 `/api/student/chat`

鉴权:`@RequireRole(STUDENT)`;数据主体固定为 token 中的 `studentId`(不接受请求体传 studentId)。Mode 由是否锚定训练决议(见设计 §5.1):锚定 → `STUDENT_STRUCTURED`,否则 `STUDENT_OPEN`。

### 3.1 新建会话 · `POST /api/student/chat/sessions`

请求 `CreateChatSessionRequest{title?, trainingSessionId?}`。

- `title` 缺省 → 服务端按首问生成(首问前留空,首次 ask 后回填)。
- `trainingSessionId` 给了则校验**归属当前学生**(该 session 的 clip 里有本人),不属于 → `40301`;锚定后该会话默认走 `STUDENT_STRUCTURED`。

响应 `ChatSessionResponse{sessionId,title,createdAt,updatedAt}`。

### 3.2 会话列表 · `GET /api/student/chat/sessions`

读 `chat_session where student_id=:current and deleted=false order by updated_at desc`,分页。响应项 `ChatSessionResponse`。

### 3.3 会话消息 · `GET /api/student/chat/sessions/{sessionId}/messages`

归属校验(`chat_session.student_id == current`,否则 `40301`)→ `chat_message where chat_session_id=? order by created_at`,分页(正序)。响应 `ChatMessageResponse{messageId,role,content,tokenUsage,createdAt}`(`tokenUsage` 仅 ASSISTANT)。

### 3.4 提问(SSE)· `POST /api/student/chat/sessions/{sessionId}/ask`

**这是学生端的主路。** 请求 `ChatAskRequest{content, trainingSessionId?}`,`Accept: text/event-stream`。

归属校验 → Mode 决议 → 落一条 USER 消息 → 组装 `AgentInvocation` → `AgentRuntime.stream(sink)`。SSE 事件(负载对齐 `ChatDtos`):

| event | 负载 | 时机 | 可丢 |
|---|---|---|---|
| `meta` | `ChatMetaEvent{messageId,sessionId}` | 落 ASSISTANT 空壳后立即,前端据此建气泡 | ✘ |
| `tool` | `ChatToolEvent{name,status,label}` | 每次工具进入/完成;`label` 为中文短句(如"查看你最近5次投篮") | ✔ |
| `delta` | `ChatDeltaEvent{text}` | LLM 逐 token | ✔(整体不可丢,单帧可合并) |
| `done` | `ChatDoneEvent{messageId,finishReason,tokenUsage,suggestions}` | 收尾;`suggestions` 为建议追问 | ✘ |
| `error` | `ErrorInfo{code,message}` | 流内异常(不走 REST 异常处理器,见技术 §9.5) | ✘ |

- `finishReason`:`stop`(正常)/`interrupted`(§3.6 中断)/`length`(达长度上限)。
- **心跳**:`: ping`/15s,防连接被中间层掐断(与教师 SSE §5.10 一致)。
- **断线**:客户端重连即重开新 ask;不做断点续传。已生成部分已落 `chat_message`,重进会话可见。
- **Token 预算**:超 Mode 预算 → 流内 `error` 事件 `TOKEN_BUDGET_EXCEEDED(42910)`,已生成部分保留。

**为什么 ask 是子资源 POST 而非顶层**:提问永远发生在某个会话下,`sessionId` 在路径里,和 §3.3 消息同级,前端心智一致。

### 3.5 会话改名 · `PATCH /api/student/chat/sessions/{sessionId}`

请求 `{title}`(≤64)。归属校验 → 改 `title`。

### 3.6 中断生成 · `POST /api/student/chat/sessions/{sessionId}/interrupt`

学生点"停止"。归属校验 → `AgentRuntime.interrupt(runId)`。当前无进行中的生成 → 幂等成功。触发 §3.4 的 `done{finishReason:"interrupted"}`。

### 3.7 删除会话 · `DELETE /api/student/chat/sessions/{sessionId}`

归属校验 → 软删(`deleted=true`,实体已有列)。幂等。**不物理删** `chat_message`:审计与 episodic memory 可能引用。

### §3 错误码

`40301` 非本人会话/锚定越权;`40400` 会话不存在;`42910` 预算耗尽;`50310` AI 不可用(LLM Gateway 降级链兜底后仍失败,§设计5.2)。

---

## §4 学生训练数据 `/api/student/training`

鉴权 `@RequireRole(STUDENT)`,数据主体固定 token `studentId`。这些端点是**对话的数据底座**:学生工具集(设计 §7.2)读的就是这几张表,前端"我的训练情况"页也读它们(前端清单见技术 §10.1)。DTO 全部在 `StudentDataDtos`。

| # | 端点 | 响应 | 读表 |
|---|---|---|---|
| 4.1 | `GET /training/overview` | `TrainingOverviewResponse` | 跨 session 聚合(与教师 §6.5 共用 service) |
| 4.2 | `GET /training/sessions` | `SessionBriefResponse`(分页) | `training_session`+clip 计数 |
| 4.3 | `GET /training/sessions/{sessionId}` | `SessionDetailResponse` | +`session_aggregate` |
| 4.4 | `GET /training/sessions/{sessionId}/clips` | `ActionClipResponse`(列表视图) | `action_clip` |
| 4.5 | `GET /training/clips/{clipId}` | `ActionClipResponse`(完整视图,含 phases/motionRange/`motionDataUrl`) | `action_clip` + MinIO 预签名 |
| 4.6 | `GET /training/sessions/{sessionId}/feedback` | `InstantFeedbackResponse`(分页) | `instant_feedback` |
| 4.7 | `GET /training/trend?actionType=&metric=` | `ProgressTrendResponse` | 跨 session 窗口聚合 |

要点:

- **归属**:所有 session/clip 必须属于当前学生(clip.student_id==current),否则 `40301`。§4.1 与教师 §6.5 **共用同一 service 方法**(教师端业务逻辑 §6.5 已注明),差别只在 studentId 来源(学生=token,教师=路径参数 + 归属校验)。
- **4.5 `motionDataUrl`**:逐帧数据在 MinIO(技术 §7.2),这里返回**短时预签名 URL**,不返回内容;LLM 工具**永不读它**(设计 §7.2 红线),只供 3D 回放前端。需 MinIO client(当前仅注释引用,见审计 D:未引依赖)。
- **`made_rate` 全 NULL 返回 null 不填 0**(教师端 §6.5 同口径)。
- **`sessionStatus < SCORED` 不报错**,原样透出(教师端 §8.1 同口径,设计 D3:用语义比较不用 ordinal)。

---

## §7 教师备课 Curriculum `/api/teacher/curriculum`(阶段二)

鉴权 `@RequireRole(TEACHER)`,`TEACHER_LESSON` Mode。模式 **Plan-and-Execute**(技术 §4.5):计划 → 教师确认/改 → 执行,用自建 `async_tasks` 状态机 + 检查点实现中断续做。**阶段一全部 `50100 NOT_IMPLEMENTED`**(与技术 §4.6/教师端 §7 的预留口径一致),此处定契约供二期落地。

### 7.1 发起备课(出大纲)· `POST /api/teacher/curriculum/plans`

请求 `{lessonId?, topic, gradeBand?, constraints?}`。创建 `async_tasks` 一条(状态 `PLANNING`),异步生成大纲。响应 `{taskId, status:"PLANNING"}`。

### 7.2 查任务(轮询/进度)· `GET /api/teacher/curriculum/plans/{taskId}`

响应 `{taskId, status, plan?, result?}`。`status ∈ {PLANNING, PLAN_READY, EXECUTING, DONE, FAILED}`。`PLAN_READY` 时 `plan` 为可编辑大纲(步骤 5~15)。

### 7.3 确认/修改计划 · `PUT /api/teacher/curriculum/plans/{taskId}/plan`

请求 `{plan}`(教师改后的大纲)。写检查点,状态 `PLAN_READY → EXECUTING`,异步生成完整教案。**这是 ReAct 做不到的"中断修改"**(技术 §4.5),Plan-and-Execute 的核心价值。

### 7.4 取产物 · `GET /api/teacher/curriculum/plans/{taskId}/document`

`status=DONE` 时返回 Word/PPT 的下载引用(`GenerateLessonDoc` 工具产出,设计 §7.3)。未完成 → `40910`。

### 7.5 备课 SSE(可选)· `GET /api/teacher/curriculum/plans/{taskId}/stream`

执行阶段的进度流(分片进度,技术 §10.1 "课末汇总 Workflow 异步(分片进度)"同思路)。事件复用 §3.4 的 `tool`/`delta`/`done`。

### §7 错误码

阶段一恒 `50100`。二期:`40301` 非本人课程(锚定 lessonId 时);`40910` 任务状态不允许该操作;`42910` 预算(50K/会话,Mode 上限)。

---

## 附:与 Cloud API v1.6 的衔接

| 本篇 | 依赖 v1.6 的 | 关系 |
|---|---|---|
| §3/§4 数据主体固定 studentId | §2 鉴权、`PENDING_ACTIVATION` 激活闸(40311) | 未激活学生连对话都进不来(先走 §2.6 激活) |
| §4 读结论层 | §10.2/§10.3 ingest 写入的 `action_clip`/`instant_feedback` | 数据由 edge/算法经 ingest 落库,Agent 只读 |
| §3.4 工具按 checkpoint 归因 / RAG 召回 | §12 词表(🚧 未实现) | **强依赖**:词表不统一则召回恒空(审计 A1) |
| §7 Curriculum | 教师端业务逻辑 §5 lesson 配置 | 备课产物可回填 lesson 的 action_types/checkpoints |

**上线门槛**:§3/§4 可在词表权威化(A1)后独立上线;§3.4 的 RAG 召回部分必须等 §12 词表落地,否则 `SearchCheckpointKnowledge` 召回恒空——此时应让 Skill Coach 降级为"只用结论层工具 + 通用知识",不硬接空 RAG。
