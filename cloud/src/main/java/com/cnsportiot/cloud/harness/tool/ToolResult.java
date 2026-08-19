package com.cnsportiot.cloud.harness.tool;

/**
 * 工具调用结果。{@link ToolRunner} 不抛异常(流式中途抛会打断 SSE),
 * 越权/失败都收敛成本类型,由各调用方决定如何呈现
 *
 * @param name    工具名
 * @param status  OK / DENIED(越权) / ERROR(执行异常或未知工具)
 * @param data    成功时的业务数据(可序列化);非 OK 时为 null
 * @param label   展示短句(取自 {@link ToolSpec#displayLabel()})
 * @param message 非 OK 时的原因说明
 */
public record ToolResult(String name, Status status, Object data, String label, String message) {

    public enum Status { OK, DENIED, ERROR }

    public boolean ok() {
        return status == Status.OK;
    }

    public static ToolResult ok(String name, Object data, String label) {
        return new ToolResult(name, Status.OK, data, label, null);
    }

    public static ToolResult denied(String name, String label, String message) {
        return new ToolResult(name, Status.DENIED, null, label, message);
    }

    public static ToolResult error(String name, String label, String message) {
        return new ToolResult(name, Status.ERROR, null, label, message);
    }
}
