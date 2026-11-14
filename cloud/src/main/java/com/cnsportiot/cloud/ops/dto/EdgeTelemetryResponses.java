package com.cnsportiot.cloud.ops.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 场边遥测运维查询响应 */
public final class EdgeTelemetryResponses {

    private EdgeTelemetryResponses() {}

    public record EdgeTelemetryLogResponse(
            UUID id,
            String eventId,
            String edgeId,
            OffsetDateTime occurredAt,
            String level,
            String source,
            String logger,
            String message,
            String errorClass,
            String errorMessage,
            String stackTrace,
            String processType,
            String processName,
            UUID runId,
            Long pid,
            String sessionId,
            UUID lessonId,
            String cameraId,
            String operation,
            Map<String, Object> attrs,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record EdgeTelemetryMetricResponse(
            UUID id,
            String eventId,
            String edgeId,
            OffsetDateTime occurredAt,
            String metricName,
            Double value,
            String unit,
            String sessionId,
            UUID lessonId,
            String cameraId,
            String processType,
            String processName,
            Map<String, Object> dims,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record EdgeProcessRunResponse(
            UUID id,
            UUID runId,
            String edgeId,
            String processType,
            String processName,
            String command,
            String workDir,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long durationMs,
            Long pid,
            Integer exitCode,
            String status,
            Long timeoutMs,
            Integer restartCount,
            Map<String, Object> attrs,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record EdgeTelemetrySummaryResponse(
            OffsetDateTime generatedAt,
            OffsetDateTime since,
            String edgeId,
            long totalLogs,
            long errorLogs,
            long warnLogs,
            long infoLogs,
            long totalMetrics,
            long totalRuns,
            long runningRuns,
            long failedRuns,
            List<EdgeProcessRunResponse> latestRuns,
            List<EdgeTelemetryLogResponse> latestErrorLogs) {}
}