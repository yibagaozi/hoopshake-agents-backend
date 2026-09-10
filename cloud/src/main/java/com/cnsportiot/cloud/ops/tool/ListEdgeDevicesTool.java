package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.ops.service.OpsService;
import com.cnsportiot.cloud.ops.dto.OpsDtos.EdgeHealth;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 运维工具(scope=OPS,只读):列场边设备,可按派生健康态过滤(ONLINE/STALE/OFFLINE)。
 * 委托 {@link OpsService#edgeDevices(EdgeHealth)}。排障常用 OFFLINE/STALE 找异常盒子
 */
@Component
public class ListEdgeDevicesTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "list_edge_devices",
            "列出场边边缘设备(附健康汇总)。health 可选:ONLINE/STALE/OFFLINE,用于只看某一类;排障时常传 OFFLINE 或 STALE "
                    + "定位掉线/心跳滞后的盒子。不传则返回全部。",
            "正在列出边缘设备…",
            """
            {"type":"object","properties":{
              "health":{"type":"string","enum":["ONLINE","STALE","OFFLINE"],"description":"按派生健康态过滤,缺省全部"}
            }}""");

    private final OpsService ops;

    public ListEdgeDevicesTool(OpsService ops) {
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
        return ops.edgeDevices(parseHealth(OpsToolArgs.getString(args, "health")));
    }

    /** 宽松解析健康态;缺省/非法 → null(表示不过滤,返回全部)。 */
    private static EdgeHealth parseHealth(String s) {
        if (s == null) {
            return null;
        }
        try {
            return EdgeHealth.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
