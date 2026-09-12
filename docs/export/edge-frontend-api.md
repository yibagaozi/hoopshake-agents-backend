# Edge API 导出(场边操作台 / 大屏 / 注册页)—— 前端适配

> 从 controllers/WS 实测导出(2026-09-10)。edge 是**局域网内**服务(默认 `:8080`),供现场三类前端:**操作台(console)**、**大屏(display)**、**注册页(registration)**。与云端分离:edge 的 `/local/**` 是 LAN 操作接口,不走云端 JWT;云端业务见 `cloud-frontend-api.md`。

## 0. 约定
- **Base**:`http://<edge-ip>:8080`。REST 前缀 `/local`;WS 见 §3。
- **信封**:同 contracts `ApiResponse{code,message,data,traceId,timestamp}`,`code=0` 成功。
- **错误码**(edge 特有,前端据此禁用按钮/提示):`40911` CV 状态冲突 · `40912` 未选课 · `40913` 名单未拉取 · `40914` 学生无特征 · `40915` 交接文件缺失(需先跑批处理) · `50200` 云端拒绝 · `50320` 云端不可达 · `50330` CV 不可用 · `50331` 批处理编排不可用 · `50340` ffmpeg 不可用 · `50341` 机位离线 · `50350` mediamtx 未就绪 · `50700` 磁盘不足 · `50701` 对象存储失败。通用码同 cloud(`40000/40910/...`)。

## 1. 操作台 REST `/local`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/local/state` | 全局状态快照:机位在线/信号/fps、CV 通道是否在线、会话状态、mediamtx/ffmpeg 状态 |
| POST | `/local/capture/restart` | 重启采集 |
| POST | `/local/lesson/select` | 选课(拉课程上下文)`{lessonId}` |
| POST | `/local/roster/sync` | 从云拉参课名单 + gallery 预取 |
| POST | `/local/roster/match` | 名单匹配(现场核对) |
| POST | `/local/enroll/start` | 开始现场人脸采集:edge 拉起算法 enroll 进程(异步)`{session(=课程id),enrollCamera?,seconds?,sampleHz?,expectedPersons?}` → `EnrollRunStatus{session,state,exitCode?,message,startedAt,finishedAt?}` |
| GET | `/local/enroll/status?session=<课程id>` | 采集状态轮询 → `EnrollRunStatus`;`state∈{RUNNING,SUCCEEDED,FAILED,NONE}`。转 SUCCEEDED 后再拉 identities |
| GET | `/local/enroll/identities?session=<课程id>` | 列出算法刚注册的人:`{session,enrollCamera,people:[{localId(stu_XX),globalId?,hasThumbnail}]}`,供“看脸绑学号”。**session 必传**,用 start 时那个课程 id |
| GET | `/local/enroll/thumbnail?session=&id=<stu_XX>` | 该人脸缩略图(image/jpeg),注册页展示用 |
| POST | `/local/enroll/bind` | 看脸输学号批量绑定 `{bindings:[{localId,globalId?,studentNo}]}` → 回 `[{localId,globalId,studentNo,studentId,displayName,matchedInRoster}]`;edge 从名单回填 studentId 并本地缓存 |
| GET | `/local/enroll/bindings` | 当前持久人脸绑定(global_id→学号),排查/回显 |
| POST | `/local/session/start` | 开始上课(建 session、建目录、拉起 4 路录制)`{lessonId?}` |
| POST | `/local/session/pause` / `/resume` / `/stop` | 暂停/继续/下课 |
| POST | `/local/session/{id}/process` | 手动:对该会话跑算法批处理并出云(需配 `batch`,异步,立即返回 accepted;未启用→`50331`) |
| POST | `/local/session/{id}/publish` | 手动:把已跑好的 `cloud/ingest.json` 直接出云(不跑批处理;交接文件不存在→`40915`) |
| GET | `/local/session/health` | 各路录制存活 |
| POST | `/local/record/start` / `/stop` · GET `/local/record/status` / `/today` | 纯录制控制(开发/无课场景) |

**状态机**(会话):`IDLE → READY(选课) → RECORDING ⇄ PAUSED → ENDED`。前端按 `/local/state` + WS `sessionStatus` 驱动 UI。

## 2. 摄像头 / 流拓扑(前端只读,后台自动)
- 4 路 dshow 采集卡 → edge 起 **mediamtx** → 每路推 `rtsp://127.0.0.1:8554/cam_01..04`(编码 nvenc)。
- 录制从 RTSP `-c copy` 存 `data/sessions/{id}/raw/cam_0X.mkv`。
- 大屏若要看画面,走 RTSP/HLS(mediamtx 提供;非本 API)。**骨架/动作叠加走 WS `poseFrame`**,不在视频流里。
- `cam_01` 左边线 · `cam_02` 右边线(v3 注册正面)· `cam_03` 底线罚球(动作切分主时钟)· `cam_04` 篮筐(进球)。

## 3. 实时 WebSocket

### 3.1 前端订阅(按角色分路径)
`ws://<edge-ip>:8080/ws/display` · `/ws/console` · `/ws/registration`。接入即收快照(如 `cameraStatus`)。每帧信封:`{ "type","seq","ts","payload" }`。

### 3.2 事件词表(payload 结构)
| type | 目标 | payload 关键字段 |
|---|---|---|
| `poseFrame` | display/console/registration | `{camId,frameNo,persons:[{studentId,displayName,confidence,keypoints:[[x,y,z,score]×17(COCO)],bbox:[x,y,w,h]归一化}]}` |
| `actionFocus` | display/console | `{studentId,displayName,studentNo,actionType,actionLabel,measured:{...}}`(大屏中央聚焦) |
| `cue` | display/console | 即时反馈提示 `{eventId,studentId,displayName,actionType,checkpointId,checkpointLabel,severity(MINOR/MAJOR/POSITIVE),cueText,measured,confidence,sourceCamera,occurredAt}` |
| `safetyAlert` | display/console | 安全告警 `{eventId,studentId,displayName,actionType,checkpointId,message,occurredAt}` |
| `cameraStatus` | display/console | `{camId,role,online,signal,fps}` |
| `sessionStatus` | display/console/registration | `{sessionId,lessonId,state,unavailableCameras[]}` |
| `enrollProgress` | registration | 预留(当前未发)。人脸采集进度改为 HTTP 轮询 `GET /local/enroll/status?session=<课程id>`,不走 WS |
| `enrollNeeded` | console/registration | 待绑定人脸提示 `{studentLocalId(stu_XX),globalId,actionType,occurredAt}`:直播里有未绑学号的面孔在投篮 → 弹“有新面孔,请输学号”,点开去 `/local/enroll/bind` |

> `poseFrame`/`actionFocus`/`cameraStatus` 为**可丢**高频类;`cue`/`safetyAlert`/`sessionStatus`/`enrollProgress` 不可丢(慢客户端会被断开)。前端渲染:骨架叠 `poseFrame`;右侧滚 `cue`;`safetyAlert` 高亮弹窗。

### 3.3 内部通道(**非前端**,CV→edge)
`/internal/cv/stream`(仅本机):算法/Mock 上行 `poseFrame`/`actionFocus`/`actionSample`(喂实时规则引擎)/`actionEvent`(逐动作事件:edge 转 `actionFocus` 上大屏 + 单条 action_clip 落库,现阶段动作级闭环)/`sessionProcessed`(触发出云)。前端**不连**此路。

## 4. 现场即时反馈"规则词表"(checkpoint)
`cue`/`safetyAlert` 由 edge 的实时 2D 规则引擎按 `checkpoints.yaml` 产出。词表条目:`{id, actionType, phase, metric, min, max, severity(MINOR/MAJOR), safety(bool), cueLow/cueHigh/cueOk, checkpointLabel, cooldownMs}`。相位词表以算法 `docs/action_phase_vocab.json` 为准。起步集:罚篮(蓄力屈膝/出手肘/出手高度/跟随)、跳投(出手肘)、上篮(终结伸展)、安全项(躯干后仰/落地屈膝)。**前端只消费产物(cue/safetyAlert),不需懂规则内部**。详见 `docs/edge/realtime-rule-engine.md`。

## 5. 现场就绪注意(前端)
- 真算法当前**不发实时事件**(见 `field-test-readiness.md` A/C):实时 WS 事件只有 mock 或后续 always-on worker 才有。前端可先对着 mock 联调 WS。
- 大屏视频与骨架叠加是两条路(RTSP + WS),前端需按时间戳松对齐。
