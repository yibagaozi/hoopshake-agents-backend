package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.ops.service.OpsService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 运维工具(scope=OPS,只读):系统健康韧性快照——LLM 是否启用、熔断状态、LLM 流舱壁(在途/可用/上限/拒绝数)、
 * /ask 令牌桶限流(拒绝数/跟踪键数)。委托 {@link OpsService#systemHealth()}。用于归因"为什么慢/为什么被拒"
 */
@Component
public class GetSystemHealthTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_system_health",
            "系统健康韧性快照:LLM 是否启用、熔断器状态(CLOSED/OPEN/HALF_OPEN)、LLM 流并发舱壁(在途/可用/上限/累计拒绝)、"
                    + "/ask 令牌桶限流(累计拒绝/跟踪键数)。用于归因对话被拒或变慢。无参。",
            "正在读取系统健康快照…",
            "{\"type\":\"object\",\"properties\":{}}");

    private final OpsService ops;

    public GetSystemHealthTool(OpsService ops) {
        this.ops = ops;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ScopeKind scope() {
        return ScopeKind.OPS;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        return ops.systemHealth();
    }
}
