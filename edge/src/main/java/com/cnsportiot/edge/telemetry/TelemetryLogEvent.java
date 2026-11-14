package com.cnsportiot.edge.telemetry;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class TelemetryLogEvent {
    private final String eventId;
    private final OffsetDateTime occurredAt;
    private final String level;
    private final String source;
    private final String logger;
    private final String message;
    private final String errorClass;
    private final String errorMessage;
    private final String stackTrace;
    private final String processType;
    private final String processName;
    private final UUID runId;
    private final Long pid;
    private final String sessionId;
    private final UUID lessonId;
    private final String cameraId;
    private final String operation;
    private final Map<String, Object> attrs;
}
