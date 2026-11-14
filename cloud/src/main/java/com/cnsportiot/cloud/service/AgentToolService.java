package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.response.AgentToolDtos.ToolInfo;
import com.cnsportiot.cloud.dto.response.AgentToolDtos.ToolInvokeResponse;
import com.cnsportiot.cloud.security.AuthUser;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具的展示与调试直调。用于确定性验证工具本身、越权闸门与审计,
 * 与对话链路共用 {@code ToolRunner},保证 Hook / 审计 / 语义一致
 */
public interface AgentToolService {

    /** 列出已注册工具(name/描述/schema) */
    List<ToolInfo> listTools();

    /** 直调一个工具(经完整 Hook 闸门 + 审计)。需开启 {@code hoopshake.agent.tools.debug-enabled} */
    ToolInvokeResponse invoke(String name, Map<String, Object> args, AuthUser user);
}
