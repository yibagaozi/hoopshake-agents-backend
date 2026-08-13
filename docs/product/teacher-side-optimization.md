# HOOPSHAKE 教师端优化建议

`v1.0 · 2026-08-13 · 针对《教师端业务逻辑 v2》`

本篇不重写教师端业务逻辑,只列**在对照仓库与其他文档后发现的、值得改的点**。分三类:必改(与代码/其他文档冲突)、体验优化、备课 Agent 接入。凡涉及底层问题的,指回 `../design-audit.md` 对应条目。

---

## 1. 必改:与代码/其他文档的冲突

### 1.1 表名 `enrollment` → `lesson_enrollment`(审计 B4)

教师端 §1 数据表、§5.6 的 SQL(`enrollment e join v_student_brief`)、§5.7/§5.8 描述用的都是 `enrollment`;但实体 `LessonEnrollment` 建的表是 **`lesson_enrollment`**(唯一约束 `uk_lesson_student`),Cloud v1.6 §5.7/附录 D 也是 `lesson_enrollment`。

**改**:教师端文档全篇 `enrollment` 表名 → `lesson_enrollment`。§5.6 SQL 相应改。这是纯文档对齐,代码不动。

### 1.2 `audit_log` 字段对齐(审计 B1)

教师端 §1 把 `audit_log` 写成 `operator_account_id / action / target_type / target_id / created_at`,但实体是 `account_id / action / target_student_id / detail(jsonb) / created_at`,且这张表还要同时承载 Agent 工具审计。

**改**:教师端 §1 的 audit_log 列改为与实体一致;建档审计的落法:

| 场景 | action | target_student_id | detail |
|---|---|---|---|
| §5.7 导入建档 | `STUDENT_AUTO_CREATE` | 新建学生 id | `{studentNo, lessonId}` |
| §6.1 单独建档 | `STUDENT_CREATE` | 新建学生 id | `{studentNo}` |

非学生目标(如未来审计 lesson 操作)放 `detail.targetType/targetId`,**不新增列**。统一设计见 `../agent/agent-system-design.md` §8。

### 1.3 建课词表(审计 A1)——教师端受害最直接

教师端 §5.1 建课校验"`actionTypes` 元素 ∈ 词表 `actions[].id`",但当前 `LessonServiceImpl` 硬编码 `shot/layup/dribble` + `stance/hand/balance`,与文档词表(`jump_shot/layup/drive/free_throw` + `elbow_alignment/…`)不一致。

**后果(教师端可见)**:教师照文档/大屏词表建课直接 `40000 包含非法训练动作`;即便建成,大屏漏渲染 `drive`/`free_throw`。

**改**:等词表权威化(A1)后,§5.1/§5.4 的校验从 `contracts/vocabulary.json` 加载,删硬编码。这是教师端建课能正常工作的前提,优先级高于备课 Agent。

---

## 2. 体验优化(不改契约,改行为/提示)

### 2.1 空档案的显著标记(呼应 Cloud v1.6 §6.1)

§5.7 导入顺带建的学生,`dominantHand`/身高/腿长为空。Cloud v1.6 §6.1 已指出:**这些不是可有可无**——CV 靠惯用手区分左右手投篮检查点,腿长参与姿态阈值归一化;缺了课上照样识别但**评估会偏且无任何报错**。

**优化**:教师端名单页(§5.6)对 `dominantHand IS NULL` 的学生给**显著标记 + 一键补全**入口(跳 §6.4)。把 §6.4 从"偶尔改身高"重新定位为"把 §5.7 建的空档案补齐"的常规动作(Cloud v1.6 §6.1 已重定位,教师端文档 §6.4 应同步这句)。

### 2.2 导入前强制预检(呼应 Cloud v1.6 §5.11)

§5.7 会**凭空建账号**,学号敲错一位就多一个查无此人的档案,还和真实档案混在一张表里,只能逐个回删(Cloud v1.6 §5.11)。

**优化**:教师端前端流程固化为 **解析 Excel → §5.11 预检 → 展示四组(`willCreate` 非空时显著提示)→ 教师确认 → §5.7 提交同一份 `students[]`**。把预检从"可选"变"默认路径",尤其 `willCreate` 非空时必须二次确认。

### 2.3 课堂实况:快照 + SSE 的一致性(§5.9/§5.10)

§5.10 SSE 断线后"重连重收 snapshot,不实现断点续传"是对的。但要补一句产品行为:**重连的 snapshot 必须与 §5.9 的 REST 快照同构**(同一 service),否则大屏(edge WS)与教师 Web(云 SSE)会渲染出不一样的实况——这与 Edge §10.1 "focus 响应与 WS payload 同结构"是同一类要求(两块屏摆一起,差异一眼可见)。

### 2.4 ReID 纠错通道(§6.6 🚧)的产品占位

§6.6 当前 `50100`。但 ReID 认错人会**污染画像**(技术 §7.3),进而污染学生端 Skill Coach 的回答(它读被污染的结论层)。

**优化**:即便一期不实现纠错逻辑,教师端也应在学生详情/名单页**预留"疑似认错"标记入口**(写一个待处理标记,不做级联清洗),让教师能先"标记"再等二期"纠正"。否则污染无声累积,到学生投诉"这不是我"时已难追溯。

---

## 3. 备课 Agent(Curriculum)接入(阶段二)

技术 §4.5/§10.1 的"备课工作台"= Curriculum Agent(Plan-and-Execute)。教师端业务逻辑 §7 目前整章 🚧(`notImplemented`)。这里给出接入形态,契约见 `../agent/cloud-agent-api.md` §7。

### 3.1 为什么教师备课用 Plan-and-Execute 而非 ReAct(技术 §4.5)

教案生成任务边界明确、步骤多(5~15)、**教师想先看大纲再决定**,且要支持"教师改 Plan 后继续"——ReAct 做不到中断修改。所以:

```
教师给主题 → Agent 出大纲(PLAN_READY)→ 教师改大纲 → 确认后生成完整教案(EXECUTING → DONE)→ 产出 Word/PPT
```

用自建 `async_tasks` 状态机 + 检查点实现中断续做,不依赖图框架(技术 §4.5)。

### 3.2 与现有教师端能力的联动

| 备课环节 | 复用现有 | 说明 |
|---|---|---|
| 生成大纲时读班级历史 | §8.1 课末汇总聚合 | Curriculum 的 `GetClassSummary` 工具复用 §8.1 口径 |
| 教案产出回填课程配置 | §5.4 更新课程 | 教案定的动作/检查点可一键写回 lesson 的 `action_types`/`enabled_checkpoints` |
| 文档生成 | Java Tool(POI/docx4j) | Skill 文件只承载教案方法论,不跑脚本沙箱(技术 §4.5) |

### 3.3 Reflection(教案质量,技术 §4.5)

Curriculum 用 **Evaluator** 型 Reflection 把关教案质量(不同于 Skill Coach 的选择性自评)。二期落地;`TIER_ADVANCED`(设计 §5.2)。

---

## 4. 汇总:教师端改动优先级

| 优先级 | 项 | 类型 | 依赖 |
|---|---|---|---|
| P0 | 1.3 建课词表 | 必改(否则建课就错) | A1 词表权威化 |
| P0 | 1.1 表名对齐 | 必改(文档) | 无 |
| P0 | 1.2 audit_log 字段对齐 | 必改(文档+审计 boot) | B1 |
| P1 | 2.2 强制预检流程 | 体验 | §5.11 已实现 |
| P1 | 2.1 空档案标记 | 体验 | 无 |
| P2 | 2.3 实况一致性 | 体验 | 无 |
| P2 | 2.4 ReID 标记占位 | 体验 | 无 |
| P3 | 3.x Curriculum 接入 | 新功能 | Harness + async_tasks |

P0 三项是"教师端现在就有毛病"的修正(建课报错、文档对不上代码);P1/P2 是打磨;P3 是二期新功能。**建议 P0 随词表/审计地基一起做,不要等备课 Agent。**
