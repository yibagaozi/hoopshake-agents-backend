package com.cnsportiot.cloud.dto.response;

/** Agent 工具相关响应 DTO(工具清单 / 调试直调结果) */
public final class AgentToolDtos {
    private AgentToolDtos() {}

    /** 工具清单项 */
    public record ToolInfo(
            String name,
            String description,
            String displayLabel,
            String inputSchema,
            boolean readOnly) {}

    /** 调试直调结果。status=ok;越权/失败经全局异常处理转 4xx/5xx */
    public record ToolInvokeResponse(
            String name,
            String status,
            Object data) {}
}
