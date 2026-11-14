package com.cnsportiot.cloud.ops.service;

import com.cnsportiot.cloud.ops.dto.EdgeTelemetryResponses.*;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryAck;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryRequest;
import com.cnsportiot.contracts.common.PageResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface EdgeTelemetryService {
    EdgeTelemetryAck ingest(EdgeTelemetryRequest request);

    PageResponse<EdgeTelemetryLogResponse> logs(
            String edgeId, String level, String source, String processType, String processName,
            UUID runId, String sessionId, UUID lessonId, String cameraId, String keyword,
            OffsetDateTime from, OffsetDateTime to, int page, int size);

    PageResponse<EdgeTelemetryMetricResponse> metrics(
            String edgeId, String metricName, String sessionId, UUID lessonId, String cameraId,
            String processType, String processName, OffsetDateTime from, OffsetDateTime to,
            int page, int size);

    PageResponse<EdgeProcessRunResponse> runs(
            String edgeId, String processType, String processName, String status,
            OffsetDateTime from, OffsetDateTime to, int page, int size);

    EdgeTelemetrySummaryResponse summary(String edgeId, int windowHours);
}
