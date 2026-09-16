package com.cnsportiot.edge.telemetry;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class TelemetryRunEvent {
    private final UUID runId;
    private final String processType;
    private final String processName;
    private final String command;
    private final String workDir;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime finishedAt;
    private final Long durationMs;
    private final Long pid;
    private final Integer exitCode;
    private final String status;
    private final Long timeoutMs;
    private final Integer restartCount;
    private final Map<String, Object> attrs;
}
