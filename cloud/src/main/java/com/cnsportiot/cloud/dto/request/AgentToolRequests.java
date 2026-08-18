package com.cnsportiot.cloud.dto.request;

import java.util.Map;

/** Agent 工具相关请求 DTO */
public final class AgentToolRequests {
    private AgentToolRequests() {}

    /** 调试直调入参(POST …/{name}/invoke)。args 为工具的原始参数,可空 */
    public record ToolInvokeRequest(Map<String, Object> args) {}
}
