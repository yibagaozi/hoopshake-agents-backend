package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.dto.response.AgentToolDtos.ToolInfo;
import com.cnsportiot.cloud.dto.response.AgentToolDtos.ToolInvokeResponse;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolRegistry;
import com.cnsportiot.cloud.harness.tool.ToolResult;
import com.cnsportiot.cloud.harness.tool.ToolRunner;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.AgentToolService;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * {@link AgentToolService} 实现。清单直接读注册表;直调经 {@link ToolRunner} 走全链路,
 * 结果按状态转 HTTP:DENIED→{@code 40301}、ERROR→{@code 50000}、OK→直出 data
 */
@Service
@RequiredArgsConstructor
public class AgentToolServiceImpl implements AgentToolService {

    private final ToolRegistry toolRegistry;
    private final ToolRunner toolRunner;
    private final AgentProperties props;

    @Override
    public List<ToolInfo> listTools() {
        return toolRegistry.all().stream()
                .map(AgentTool::spec)
                .map(s -> new ToolInfo(s.name(), s.description(), s.displayLabel(), s.inputSchema(), s.readOnly()))
                .toList();
    }

    @Override
    public ToolInvokeResponse invoke(String name, Map<String, Object> args, AuthUser user) {
        if (!props.getTools().isDebugEnabled()) {
            throw new BusinessException(ErrorCode.NOT_IMPLEMENTED,
                    "工具调试端点未开放(设 hoopshake.agent.tools.debug-enabled=true 开启)");
        }
        if (toolRegistry.find(name).isEmpty()) {
            throw BusinessException.notFound("工具不存在: " + name);
        }

        ToolContext ctx = ToolContext.of(user.accountId(), user.studentId());
        ToolResult result = toolRunner.run(name, args, ctx, null);

        return switch (result.status()) {
            case OK -> new ToolInvokeResponse(result.name(), "ok", result.data());
            case DENIED -> throw BusinessException.dataScopeDenied();
            case ERROR -> throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    result.message() == null ? "工具执行失败" : result.message());
        };
    }
}
