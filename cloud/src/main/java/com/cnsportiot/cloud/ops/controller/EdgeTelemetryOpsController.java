package com.cnsportiot.cloud.ops.controller;

import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryResponses.EdgeProcessRunResponse;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryResponses.EdgeTelemetryLogResponse;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryResponses.EdgeTelemetryMetricResponse;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryResponses.EdgeTelemetrySummaryResponse;
import com.cnsportiot.cloud.ops.service.EdgeTelemetryService;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 场边遥测运维查询(ADMIN)。 */
@RestController
@RequestMapping("/api/ops/edge/telemetry")
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class EdgeTelemetryOpsController {

    private final EdgeTelemetryService telemetryService;

    @GetMapping("/summary")
    public ApiResponse<EdgeTelemetrySummaryResponse> summary(
            @RequestParam(required = false) String edgeId,
            @RequestParam(name = "windowHours", defaultValue = "24") int windowHours) {
        return ApiResponse.ok(telemetryService.summary(edgeId, windowHours));
    }

    @GetMapping("/logs")
    public ApiResponse<PageResponse<EdgeTelemetryLogResponse>> logs(
            @RequestParam(required = false) String edgeId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String processType,
            @RequestParam(required = false) String processName,
            @RequestParam(required = false) UUID runId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) UUID lessonId,
            @RequestParam(required = false) String cameraId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(telemetryService.logs(edgeId, level, source, processType,
                processName, runId, sessionId, lessonId, cameraId, keyword, from, to, page, size));
    }

    @GetMapping("/metrics")
    public ApiResponse<PageResponse<EdgeTelemetryMetricResponse>> metrics(
            @RequestParam(required = false) String edgeId,
            @RequestParam(required = false) String metricName,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) UUID lessonId,
            @RequestParam(required = false) String cameraId,
            @RequestParam(required = false) String processType,
            @RequestParam(required = false) String processName,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(telemetryService.metrics(edgeId, metricName, sessionId, lessonId,
                cameraId, processType, processName, from, to, page, size));
    }

    @GetMapping("/runs")
    public ApiResponse<PageResponse<EdgeProcessRunResponse>> runs(
            @RequestParam(required = false) String edgeId,
            @RequestParam(required = false) String processType,
            @RequestParam(required = false) String processName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(telemetryService.runs(edgeId, processType, processName,
                status, from, to, page, size));
    }
}
