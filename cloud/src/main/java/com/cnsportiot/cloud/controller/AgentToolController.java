package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.AgentToolRequests.ToolInvokeRequest;
import com.cnsportiot.cloud.dto.response.AgentToolDtos.ToolInfo;
import com.cnsportiot.cloud.dto.response.AgentToolDtos.ToolInvokeResponse;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.AgentToolService;
import com.cnsportiot.contracts.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 工具:清单 + 调试直调。学生态,数据主体固定为 token 中的 studentId
 *
 * 直调端点不经 LLM,便于确定性验证工具/越权闸门/审计;默认关闭,由
 * {@code hoopshake.agent.tools.debug-enabled} 控制开启
 */
@RestController
@RequestMapping("/api/student/agent/tools")
@RequireRole(Role.STUDENT)
public class AgentToolController {

    private final AgentToolService agentToolService;

    public AgentToolController(AgentToolService agentToolService) {
        this.agentToolService = agentToolService;
    }

    /** 工具清单(name/描述/schema)。始终可用 */
    @GetMapping
    public ApiResponse<List<ToolInfo>> list() {
        return ApiResponse.ok(agentToolService.listTools());
    }

    /** 调试直调一个工具(经完整 Hook 闸门 + 审计)。越权得 40301,失败得 50000 */
    @PostMapping("/{name}/invoke")
    public ApiResponse<ToolInvokeResponse> invoke(
            @PathVariable String name,
            @RequestBody(required = false) ToolInvokeRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(agentToolService.invoke(
                name, request == null ? null : request.args(), me));
    }
}

