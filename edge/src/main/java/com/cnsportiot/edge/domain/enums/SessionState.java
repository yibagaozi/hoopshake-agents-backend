package com.cnsportiot.edge.domain.enums;

/**
 * 场边课堂会话状态机:IDLE → READY → RECORDING ⇄ PAUSED → ENDED
 *
 * <p>与云端 SessionStatus 不同,后者是算法 pipeline 的处理进度(Cloud API 附录 A)。
 * 允许的跃迁:
 * <pre>
 * IDLE      --选课--------→ READY
 * IDLE      --直接开录----→ RECORDING   (开发阶段的纯录制,lessonId 为 null)
 * READY     --开始上课----→ RECORDING
 * RECORDING --暂停--------→ PAUSED
 * PAUSED    --继续--------→ RECORDING
 * RECORDING --下课--------→ ENDED
 * PAUSED    --下课--------→ ENDED
 * ENDED     --选课/开录--→ READY / RECORDING   (开始新一节课)
 * </pre>
 */
public enum SessionState {

    /** 未选课或未开始 */
    IDLE,

    /** 已选课、名单已拉取,尚未开录 */
    READY,

    /** 上课中(录制进行) */
    RECORDING,

    /** 暂停:录制挂起,课未结束;继续后生成新一段录制文件 */
    PAUSED,

    /** 已下课 */
    ENDED
}
