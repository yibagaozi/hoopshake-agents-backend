# 算法侧改动方案:直播 WS 由“算法起服务”改为“算法连 edge 推送”

> 面向 `jxk6575/Basketball_inclass_system` v2.2.0。v2.2.0 现在是算法自己起 WS 服务(`WsHub`,127.0.0.1:8765),等别人来连。
> 我们的架构沿用原设计:**edge 起 WS 服务端,算法作客户端连上来推事件**。所以算法只需改“输出这一段”,
> 推理/RTSP/同步/标定/注册/动作 finalize/角度全部不动。

## 1. 一句话改动

把 `WsHub`(广播服务端)换成一个“连 edge 并推送”的客户端 sink,**保持同样的接口**(`publish(event)` / `start_background()` / `stop()`)。
`LiveEngine` 一行不用改——它只调 `hub.publish(event_dict)`。

- 连接地址:`ws://127.0.0.1:8080/internal/cv/stream`(edge 的内部通道,仅本机可连;算法与 edge 同机)。
- 每条事件包一层 edge 信封再发:`{"type": <名>, "seq": <递增>, "ts": <ISO时间>, "payload": <算法原来的事件 dict 原样放进来>}`。
- `type` 取值:`action_finalized` → `"actionFinalized"`;`timeline_gap` → `"timelineGap"`。
- **payload 就是你现在 `build_action_event` / `build_gap_event` 产出的那个 dict,原样塞进去,字段一个都不用改名**(edge 认 snake_case:`session_id/student_id/global_id/action_type/start_ms/.../angles/phases`)。

## 2. 具体怎么改

新增一个 `src/streaming/edge_ws_client.py`(和 `WsHub` 同接口),`run_live_ws.py` 的 `cmd_run` 里把 `WsHub(...)` 换成它即可:

```python
# src/streaming/edge_ws_client.py
import asyncio, json, threading, time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
import websockets  # 已是依赖

TYPE_MAP = {"action_finalized": "actionFinalized", "timeline_gap": "timelineGap"}

class EdgeWsClient:
    """与 WsHub 同接口,但作为客户端连 edge 的 /internal/cv/stream 推事件。"""
    def __init__(self, url: str = "ws://127.0.0.1:8080/internal/cv/stream",
                 *, jsonl_path: Path | None = None, reconnect_sec: float = 2.0):
        self.url = url
        self.jsonl_path = Path(jsonl_path) if jsonl_path else None
        if self.jsonl_path:
            self.jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        self.reconnect_sec = reconnect_sec
        self._loop = None; self._thread = None; self._ws = None
        self._seq = 0; self._running = False

    def start_background(self):
        self._running = True
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        self._loop.run_until_complete(self._connect_loop())

    async def _connect_loop(self):
        while self._running:
            try:
                async with websockets.connect(self.url, max_size=None) as ws:
                    self._ws = ws
                    # 只上行,不需要读;保持连接直到断开
                    while self._running:
                        await asyncio.sleep(0.5)
            except Exception as e:      # edge 未起/断开 → 重连
                self._ws = None
                await asyncio.sleep(self.reconnect_sec)

    def publish(self, event: dict[str, Any]) -> None:
        # 落本地 jsonl(可选,和原来一样)
        if self.jsonl_path:
            with self.jsonl_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(event, ensure_ascii=False) + "\n")
        self._seq += 1
        frame = {
            "type": TYPE_MAP.get(event.get("event"), event.get("event")),
            "seq": self._seq,
            "ts": datetime.now(timezone.utc).isoformat(),
            "payload": event,                    # ← 原事件 dict 原样,字段不改名
        }
        loop, ws = self._loop, self._ws
        if loop is None or ws is None:
            return                                # 未连上就丢这条(edge 断线;实时数据可丢)
        asyncio.run_coroutine_threadsafe(ws.send(json.dumps(frame, ensure_ascii=False)), loop)

    def stop(self):
        self._running = False
```

`run_live_ws.py` 的改动(只有 import + 一行构造):
```python
# from src.streaming.ws_hub import WsHub
from src.streaming.edge_ws_client import EdgeWsClient
...
# hub = WsHub(host, port, jsonl_path=jsonl)
hub = EdgeWsClient(edge_url, jsonl_path=jsonl)   # edge_url 从 configs/live.yaml 读,默认上面那个
hub.start_background()
```
`configs/live.yaml` 把 `websocket:` 段换成(或新增)一个 `edge_ws_url: ws://127.0.0.1:8080/internal/cv/stream`。`LiveEngine(...)` 传的 `hub=` 不变。

就这些。`build_action_event`/`build_gap_event`/角度/相位/身份全都不动。

## 3. edge 收到后会做什么(你不用管,给你对齐用)

edge 的 `/internal/cv/stream` 收到帧 → 按 `type` 路由 → `actionFinalized` 交 `LiveActionListener`:
1. 用 `global_id`(优先,跨课次)或 `student_id`(stu_XX,当堂)查绑定 → 学号 → studentId;
2. 发大屏 `actionFocus`(谁、什么动作、命中);
3. 把每相位的 angles 折成测量值喂实时规则引擎 → 出 cue/安全提示 + 入 instant_feedback;
4. 落一条 action_clip(带 `score.release_angles`,供云端算标准度),供 agent 分析。

## 4. 人脸→学号绑定(edge 侧已做,算法基本不用改)

- 算法 `enroll` 产出的 `data/outputs/live/{session}/enrollment.json` + `enroll_preview/{stu}.jpg`,edge 直接读盘展示,教师“看脸输学号”,edge 缓存 `global_id→学号→studentId`(本地,不上云)。**算法不用为绑定加接口。**
- **唯一建议算法做的小改(强烈建议)**:在 `enrollment.json` 里,除了 `student_ids`(stu_XX),再给出每个 stu_XX 对应的 `global_id`。否则 edge 只能等 run 时该生投了篮、事件带回 global_id 才学到跨课次映射;某人注册了但当堂没投篮,它的 `global_id→学号` 就建不起来,下次课要重绑。给了 global_id,注册当场就能建好跨课次映射。

  建议格式:
  ```json
  {
    "session_id": "live_demo",
    "enroll_camera": "cam_02",
    "student_ids": ["stu_00", "stu_01"],
    "identities": [
      {"local_id": "stu_00", "global_id": "stu_global_03"},
      {"local_id": "stu_01", "global_id": "stu_global_07"}
    ]
  }
  ```

## 5. 算法侧“硬伤”/现场强依赖(需要你们知道并权衡)

1. **不标定就没有角度**:`run` 前必须做 GUI 标定(`calibrate`),否则每个动作的 `angles[]` 全是 `null` → edge 只能给“动作级”提示(命中/未中/计数),给不了“肘角/膝角”级。标定是人工点选、需 DISPLAY。这是现场最大的前置依赖。
2. **setup 三步都要人工 + DISPLAY**(`sync` 同步 GUI、`calibrate` 标注、`enroll` 预览),不能全自动;现场算法机要有人操作图形界面。
3. **四路 1080p 同机串行 YOLO 可能跟不上 30fps**(你们自述已知):对齐优先、有界队列丢帧,端到端延迟可能偏大,实时提示会滞后。
4. **直播动作检测是环形缓冲近实时,召回/NMS 弱于离线**:漏检/误检比课后批处理多,现场提示会有假阳/假阴。
5. **`made`(进球)是 cam_04 简化几何**:命中判定不如离线遮挡否决准,大屏“命中/未中”可能偶错。
6. **身份桥接依赖 enroll 顺序 + 人工绑**:enroll 若把两人并成一个 / 一人拆两个,绑定就错位。edge 用缩略图“看脸绑”缓解,但 enroll 质量是前提。
7. **`enrollment.json` 目前不含 global_id**(见 §4 建议):不改的话跨课次映射只能靠 run 时补学,注册了没投篮的人建不起映射。
8. **腕角**:2D/COCO 下不可信,edge 实时层本就不评(放课后批处理)。

## 6. 现场最小可行路径

1. 算法机:`sync` → `calibrate`(想要角度就必须做)→ `enroll`(顺序正面注册)。
2. edge 起 mediamtx 把 4 路采集卡推成 `rtsp://127.0.0.1:8554/cam_0X`(算法 `configs/live.yaml` 的 rtsp 指向它)。
3. 教师在 edge 注册页:`GET /local/enroll/identities?session=<enrollSession>` 看脸 → `POST /local/enroll/bind` 逐个输学号。
4. 算法 `run`(用改好的 EdgeWsClient 连 edge)→ 投篮 → edge 出大屏提示 + 落库 → 学生/教师端与 agent 看数据。
5. 没做标定就只有动作级提示(命中/未中/计数);做了标定才有肘角/膝角级。

> 前置:云端需已支持“按学号入库”(ingest 接受 studentNo,服务端解析 studentId),这块 edge/cloud 已实现。
