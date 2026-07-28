package com.cnsportiot.edge.realtime;

/** WS 客户端角色,决定该连接收哪些事件 */
public enum WsRole {

    /** 课堂大屏 /ws/display */
    DISPLAY,

    /** 教师操作台平板 /ws/console */
    CONSOLE,

    /** 现场注册页 /ws/registration */
    REGISTRATION;

    public static WsRole fromPath(String path) {
        if (path == null) {
            return CONSOLE;
        }
        if (path.endsWith("/display")) {
            return DISPLAY;
        }
        if (path.endsWith("/registration")) {
            return REGISTRATION;
        }
        return CONSOLE;
    }
}
