package com.cnsportiot.cloud.ops.controller;

import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.ops.service.OpsService;
import com.cnsportiot.cloud.ops.dto.OpsDtos.*;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 运维只读看板接口。ADMIN 角色;全部 GET、无副作用
 * 供运维 Vue 台消费:首屏 {@link #overview},子卡片按需刷新对应子端点
 */
@RestController
@RequestMapping("/api/ops")
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class OpsController {

    private final OpsService opsService;

    /** 4.1 首屏一次拉全。*/
    @GetMapping("/overview")
    public ApiResponse<OverviewResponse> overview(
            @RequestParam(name = "agentWindowHours", defaultValue = "24") int agentWindowHours) {
        return ApiResponse.ok(opsService.overview(agentWindowHours));
    }

    /** 4.2 业务量 */
    @GetMapping("/business")
    public ApiResponse<BusinessResponse> business() {
        return ApiResponse.ok(opsService.business());
    }

    /** 4.3 Agent 表现近窗 */
    @GetMapping("/agent/quality")
    public ApiResponse<AgentQualityResponse> agentQuality(
            @RequestParam(name = "windowHours", defaultValue = "24") int windowHours) {
        return ApiResponse.ok(opsService.agentQuality(windowHours));
    }

    /** 4.4 系统健康(韧性快照) */
    @GetMapping("/system")
    public ApiResponse<SystemHealthResponse> system() {
        return ApiResponse.ok(opsService.systemHealth());
    }

    /** 4.5 边缘设备列表(可按派生健康态过滤) */
    @GetMapping("/edge/devices")
    public ApiResponse<EdgeDeviceListResponse> edgeDevices(
            @RequestParam(name = "health", required = false) EdgeHealth health) {
        return ApiResponse.ok(opsService.edgeDevices(health));
    }

    /** 4.5 单设备详情 */
    @GetMapping("/edge/devices/{deviceId}")
    public ApiResponse<EdgeDeviceResponse> edgeDevice(@PathVariable String deviceId) {
        EdgeDeviceResponse d = opsService.edgeDevice(deviceId);
        if (d == null) {
            throw BusinessException.notFound("设备不存在: " + deviceId);
        }
        return ApiResponse.ok(d);
    }
}
