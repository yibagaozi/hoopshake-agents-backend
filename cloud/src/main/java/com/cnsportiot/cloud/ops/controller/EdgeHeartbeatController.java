package com.cnsportiot.cloud.ops.controller;

import com.cnsportiot.cloud.ops.service.EdgeHeartbeatService;
import com.cnsportiot.cloud.ops.dto.EdgeHeartbeatRequest;
import com.cnsportiot.cloud.ops.dto.OpsDtos.EdgeHeartbeatAck;
import com.cnsportiot.contracts.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场边设备心跳上报。位于 {@code /api/ingest/edge/**},由 {@code ServiceTokenFilter} 校验
 * {@code X-Service-Token}(与其它 ingest 端点同源),不走用户 JWT
 */
@RestController
@RequestMapping("/api/ingest/edge")
@RequiredArgsConstructor
public class EdgeHeartbeatController {

    private final EdgeHeartbeatService heartbeatService;

    @PostMapping("/heartbeat")
    public ApiResponse<EdgeHeartbeatAck> heartbeat(
            @Valid @RequestBody EdgeHeartbeatRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(heartbeatService.heartbeat(request, clientIp(http)));
    }

    /** 取来源 IP:优先 X-Forwarded-For 首段(经网关时),否则 remoteAddr */
    private static String clientIp(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return http.getRemoteAddr();
    }
}
