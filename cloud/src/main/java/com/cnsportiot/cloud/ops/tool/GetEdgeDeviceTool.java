package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.ops.service.OpsService;
import com.cnsportiot.cloud.ops.dto.OpsDtos.EdgeDeviceResponse;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 运维工具(scope=OPS,只读):单设备明细——cpu/温度/fps 等指标、上报状态、派生健康态、版本、lastError、lastSeenAt。
 * 委托 {@link OpsService#edgeDevice(String)}。设备不存在时返回 {@link NotFound} 说明,不抛错(便于模型据实回答)
 */
@Component
public class GetEdgeDeviceTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_edge_device",
            "查单台场边设备明细:上报状态、派生健康态、cpu/温度/fps 等指标、app/固件版本、ip、lastError、最后心跳时间。"
                    + "deviceId 必填(可先用 list_edge_devices 拿到)。设备不存在时返回 found=false 说明。",
            "正在查看该设备明细…",
            """
            {"type":"object","properties":{
              "deviceId":{"type":"string","description":"设备唯一标识"}
            },"required":["deviceId"]}""");

    private final OpsService ops;

    public GetEdgeDeviceTool(OpsService ops) {
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
        String deviceId = OpsToolArgs.getString(args, "deviceId");
        if (deviceId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "deviceId 缺失");
        }
        EdgeDeviceResponse d = ops.edgeDevice(deviceId);
        if (d == null) {
            return new NotFound(deviceId, false, "设备不存在或从未上报心跳");
        }
        return d;
    }

    /** 设备不存在时的兜底结果(可 Jackson 序列化)。 */
    public record NotFound(String deviceId, boolean found, String note) {}
}
