package com.cnsportiot.edge.realtime;

import java.util.Set;

/** WS 事件类型,同时声明投递目标与背压策略 */
public enum WsEventType {

    /** 3D 骨架帧,高频 */
    POSE_FRAME("poseFrame", true, Set.of(WsRole.DISPLAY, WsRole.CONSOLE, WsRole.REGISTRATION)),

    /** 当前聚焦的学生与动作 */
    ACTION_FOCUS("actionFocus", true, Set.of(WsRole.DISPLAY, WsRole.CONSOLE)),

    /**
     * 动作评测采样(CV 上行):某学生某相位的 2D 可算量,喂实时规则引擎。
     * 仅入站、不直接扇出 UI(targets 为空 → WsHub 不广播),由 {@code RuleEngineListener} 消费。
     */
    ACTION_SAMPLE("actionSample", false, Set.of()),

    /**
     * 会话批处理完成(CV 上行,payload 至少含 {@code sessionId}):触发"会话结束出云"。
     * 仅入站、不扇出 UI,由 {@code SessionPublishListener} 消费。
     */
    SESSION_PROCESSED("sessionProcessed", false, Set.of()),

    /**
     * 逐动作事件(CV 上行,一个动作完成发一条):现阶段"实时动作级"闭环的载体。
     * 仅入站、不直接扇出 UI(由 {@code ActionClipForwarder} 消费):当场①转 {@link #ACTION_FOCUS} 上大屏、
     * ②作为单条 action_clip 落库,供 agent 简易分析。payload 结构见 {@code WsEvents.ActionEvent}。
     */
    ACTION_EVENT("actionEvent", false, Set.of()),

    /**
     * 算法 v2.2.0 直播 {@code action_finalized}(edge 作 WS 客户端从 {@code ws://127.0.0.1:8765} 收):
     * 一个投篮动作 finalize 一条,含 phases + 每相位 angles[] + 身份(stu_XX/global_id)+ made。
     * 仅入站,由 {@code LiveActionListener} 消费:身份绑定解析 → 大屏 actionFocus → angles 喂规则引擎出 cue
     * → 单条 action_clip 落库(score.release_angles 供云端派生标准度)。payload 见 {@code WsEvents.ActionFinalized}。
     */
    ACTION_FINALIZED("actionFinalized", false, Set.of()),

    /** 算法直播断流空洞({@code timeline_gap}):仅入站,记日志/大屏提示,不落库。 */
    TIMELINE_GAP("timelineGap", false, Set.of()),

    /**
     * 待绑定人脸提示:直播中出现了没绑学号的身份(stu_XX/global_id)在投篮,提醒操作台/注册页“有新面孔,请输学号”。
     * 由 {@code LiveActionListener} 在解析不到绑定时发(按身份去抖,不刷屏)。payload 见 {@code WsEvents.EnrollNeeded}。
     */
    ENROLL_NEEDED("enrollNeeded", false, Set.of(WsRole.CONSOLE, WsRole.REGISTRATION)),

    /** 即时反馈提示 */
    CUE("cue", false, Set.of(WsRole.DISPLAY, WsRole.CONSOLE)),

    /** 安全告警 */
    SAFETY_ALERT("safetyAlert", false, Set.of(WsRole.DISPLAY, WsRole.CONSOLE)),

    /** 机位在线/信号/帧率 */
    CAMERA_STATUS("cameraStatus", true, Set.of(WsRole.DISPLAY, WsRole.CONSOLE)),

    /** 会话状态跃迁 */
    SESSION_STATUS("sessionStatus", false,
            Set.of(WsRole.DISPLAY, WsRole.CONSOLE, WsRole.REGISTRATION)),

    /** 现场注册采集进度 */
    ENROLL_PROGRESS("enrollProgress", false, Set.of(WsRole.REGISTRATION));

    private final String wireName;
    private final boolean droppable;
    private final Set<WsRole> targets;

    WsEventType(String wireName, boolean droppable, Set<WsRole> targets) {
        this.wireName = wireName;
        this.droppable = droppable;
        this.targets = targets;
    }

    public String wireName() {
        return wireName;
    }

    public boolean droppable() {
        return droppable;
    }

    public Set<WsRole> targets() {
        return targets;
    }

    /** 按线上名查枚举,用于解析 CV 上行事件;未知类型返回 null 由调用方决定忽略还是告警 */
    public static WsEventType fromWire(String wireName) {
        for (WsEventType t : values()) {
            if (t.wireName.equals(wireName)) {
                return t;
            }
        }
        return null;
    }
}

