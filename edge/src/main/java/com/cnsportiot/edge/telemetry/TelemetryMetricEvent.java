package com.cnsportiot.edge.telemetry;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class TelemetryMetricEvent {
    private final String eventId;
    private final OffsetDateTime occurredAt;
    private final String metricName;
    private final Double value;
    private final String unit;
    private final String sessionId;
    private final UUID lessonId;
    private final String cameraId;
    private final String processType;
    private final String processName;
    private final Map<String, Object> dims;
}
