package com.cnsportiot.cloud.ops.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 场边遥测批量上报。三类数据均可为空,由 edge 按批量/间隔聚合。 */
public record EdgeTelemetryRequest(
        @NotBlank @Size(max = 64) String edgeId,
        @NotNull OffsetDateTime reportedAt,
        @Size(max = 1000) @Valid List<LogItem> logs,
        @Size(max = 1000) @Valid List<MetricItem> metrics,
        @Size(max = 1000) @Valid List<RunItem> runs) {

    public record LogItem(
            @NotBlank @Size(max = 64) String eventId,
            @NotNull OffsetDateTime occurredAt,
            @NotBlank @Size(max = 8) String level,
            @NotBlank @Size(max = 16) String source,
            @Size(max = 255) String logger,
            @Size(max = 8192) String message,
            @Size(max = 255) String errorClass,
            @Size(max = 4096) String errorMessage,
            @Size(max = 16384) String stackTrace,
            @Size(max = 32) String processType,
            @Size(max = 64) String processName,
            UUID runId,
            Long pid,
            @Size(max = 64) String sessionId,
            UUID lessonId,
            @Size(max = 32) String cameraId,
            @Size(max = 64) String operation,
            Map<String, Object> attrs) {}

    public record MetricItem(
            @NotBlank @Size(max = 64) String eventId,
            @NotNull OffsetDateTime occurredAt,
            @NotBlank @Size(max = 128) String metricName,
            @NotNull Double value,
            @Size(max = 16) String unit,
            @Size(max = 64) String sessionId,
            UUID lessonId,
            @Size(max = 32) String cameraId,
            @Size(max = 32) String processType,
            @Size(max = 64) String processName,
            Map<String, Object> dims) {}

    public record RunItem(
            @NotNull UUID runId,
            @NotBlank @Size(max = 32) String processType,
            @NotBlank @Size(max = 64) String processName,
            @Size(max = 4096) String command,
            @Size(max = 1024) String workDir,
            @NotNull OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long durationMs,
            Long pid,
            Integer exitCode,
            @NotBlank @Size(max = 24) String status,
            Long timeoutMs,
            Integer restartCount,
            Map<String, Object> attrs) {}
}
