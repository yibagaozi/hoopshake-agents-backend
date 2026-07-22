package com.cnsportiot.cloud.dto.request;

import com.cnsportiot.cloud.domain.enums.FeedbackSeverity;
import com.cnsportiot.cloud.domain.enums.SessionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 服务间入库请求 DTO */
public final class IngestRequests {
    private IngestRequests() {}

    /** 10.1 Session 生命周期上报(状态只前进不回退) */
    public record UpsertSessionRequest(
            UUID lessonId,
            @NotNull SessionStatus status,
            Map<String, Object> coordinateSystem,
            @Size(max = 255) String cameraConfigRef,
            @Size(max = 255) String dataDir,
            @Size(max = 512) String motionExportUri,
            OffsetDateTime recordedAt,
            OffsetDateTime generatedAt,
            Map<String, Object> metadata) {}

    /** 10.2 SessionOutput 入库(HTTP 补传通道) */
    public record SessionOutputIngestRequest(
            @NotBlank String eventId,
            @NotNull UUID sessionId,
            @NotBlank String schemaVersion,
            OffsetDateTime producedAt,
            @NotNull Map<String, Object> payload) {}

    /** 10.3 即时反馈批量落库(逐条以 eventId 幂等) */
    public record InstantFeedbackBatchRequest(
            UUID sessionId,
            @NotEmpty @Valid List<Item> items) {

        public record Item(
                @NotBlank String eventId,
                @NotNull UUID studentId,
                @NotNull OffsetDateTime occurredAt,
                BigDecimal timestampMs,
                @Size(max = 24) String actionType,
                @Size(max = 64) String checkpointId,
                FeedbackSeverity severity,
                @Size(max = 255) String cueText,
                Map<String, Object> measured,
                BigDecimal confidence,
                @Size(max = 16) String sourceCamera) {}
    }

    /** 10.4 ReID gallery 登记(敏感操作,记 audit_log) */
    public record RegisterGalleryRequest(
            @NotNull UUID studentId,
            Integer version,
            @NotBlank @Size(max = 512) String storageUri,
            @Size(max = 64) String faceModel,
            @Size(max = 64) String bodyModel,
            Integer faceDim,
            Integer bodyDim,
            Integer sampleCount,
            @Size(max = 16) String enrolledCamera,
            OffsetDateTime enrolledAt,
            UUID operatorAccountId) {}

    /** 10.6 边缘心跳 */
    public record EdgeHeartbeatRequest(
            @NotBlank String edgeId,
            @NotNull OffsetDateTime reportedAt,
            @Valid List<CameraStatus> cameras,
            Map<String, Object> versions) {}

    public record CameraStatus(
            @NotBlank String cameraId,
            boolean online) {}
}

