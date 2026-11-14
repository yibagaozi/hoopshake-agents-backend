package com.cnsportiot.cloud.ops.dto;

public record EdgeTelemetryAck(
        String edgeId,
        int acceptedLogs,
        int duplicatedLogs,
        int acceptedMetrics,
        int duplicatedMetrics,
        int acceptedRuns,
        int updatedRuns) {}
