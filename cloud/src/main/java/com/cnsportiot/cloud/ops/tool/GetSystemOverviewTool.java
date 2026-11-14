package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.ops.service.OpsService;
import com.cnsportiot.cloud.ops.config.OpsProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 运维工具(scope=OPS,只读):首屏总览——业务量 + Agent 表现 + 系统健康 + 边缘汇总,一次拉全。
 * 委托 {@link OpsService#overview(int)},与 REST 运维台同源。诊断第一步:先看全局再下钻
 */
@Component
public class GetSystemOverviewTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_system_overview",
            "系统首屏总览:一次返回业务量(课程/学生/教师/数据量)、Agent 近窗表现、系统健康(熔断/舱壁/限流)、"
                    + "边缘设备汇总。健康巡检/排障的第一步,先看全局再下钻。无参。",
            "正在拉取系统总览…",
            "{\"type\":\"object\",\"properties\":{}}");

    private final OpsService ops;
    private final OpsProperties props;

    public GetSystemOverviewTool(OpsService ops, OpsProperties props) {
        this.ops = ops;
        this.props = props;
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
        return ops.overview(props.getAgent().getDefaultWindowHours());
    }
}
