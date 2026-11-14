package com.cnsportiot.cloud.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 边缘盒子心跳上报(POST /api/ingest/edge/heartbeat)。按 {@code deviceId} 幂等 upsert
 * 除 deviceId 外全部可空;{@code occurredAt} 缺省服务端 now;{@code metrics} 弹性透传
 */
public record EdgeHeartbeatRequest(
        @NotBlank @Size(max = 64) String deviceId,
        @Size(max = 64) String name,
        @Size(max = 64) String courtId,
        @Size(max = 24) String reportedStatus,
        @Size(max = 32) String appVersion,
        @Size(max = 32) String firmware,
        Map<String, Object> metrics,
        @Size(max = 255) String lastError,
        OffsetDateTime occurredAt) {
}
