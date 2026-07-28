package com.cnsportiot.edge.realtime;

import java.util.Set;

/** WS 事件类型,同时声明投递目标与背压策略 */
public enum WsEventType {

    /** 3D 骨架帧,高频 */
    POSE_FRAME("poseFrame", true, Set.of(WsRole.DISPLAY, WsRole.CONSOLE, WsRole.REGISTRATION)),

    /** 当前聚焦的学生与动作 */
    ACTION_FOCUS("actionFocus", true, Set.of(WsRole.DISPLAY, WsRole.CONSOLE)),

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

