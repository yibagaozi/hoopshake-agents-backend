package com.cnsportiot.cloud.dto.request;

import com.cnsportiot.contracts.enums.FeedbackSeverity;
import com.cnsportiot.contracts.enums.SessionStatus;
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

        /**
         * 身份可用 studentId(UUID)或 studentNo(学号)二选一;服务端优先 studentId,
         * 缺失时按 studentNo 查 student 表解析。两者都缺/学号查不到→该条 rejected
         */
        public record Item(
                @NotBlank String eventId,
                UUID studentId,
                @NotNull OffsetDateTime occurredAt,
                BigDecimal timestampMs,
                @Size(max = 24) String actionType,
                @Size(max = 64) String checkpointId,
                FeedbackSeverity severity,
                @Size(max = 255) String cueText,
                Map<String, Object> measured,
                BigDecimal confidence,
                @Size(max = 16) String sourceCamera,
                @Size(max = 32) String studentNo) {

            /** 兼容旧构造(仅 studentId,无 studentNo) */
            public Item(String eventId, UUID studentId, OffsetDateTime occurredAt, BigDecimal timestampMs,
                        String actionType, String checkpointId, FeedbackSeverity severity, String cueText,
                        Map<String, Object> measured, BigDecimal confidence, String sourceCamera) {
                this(eventId, studentId, occurredAt, timestampMs, actionType, checkpointId, severity,
                        cueText, measured, confidence, sourceCamera, null);
            }
        }
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

    /** 10.2 ActionClip 批量入库(以 sessionId+studentId+clipIndex 幂等) */
    public record ActionClipBatchRequest(
            @NotNull UUID sessionId,
            @NotEmpty @Valid List<ClipItem> items) {

        /**
         * 身份可用 studentId(UUID)或 studentNo(学号)二选一;服务端优先 studentId,
         * 缺失时按 studentNo 查 student 表解析。两者都缺/学号查不到→该条 rejected
         */
        public record ClipItem(
                UUID studentId,
                @NotNull Integer clipIndex,
                @NotBlank @Size(max = 24) String actionType,
                @NotNull BigDecimal startMs,
                @NotNull BigDecimal endMs,
                BigDecimal releaseMs,
                @Size(max = 16) String anchorCamera,
                @Size(max = 32) String zoneId,
                List<Map<String, Object>> phases,
                Boolean shotMade,
                Map<String, Object> score,
                @Size(max = 512) String motionUri,
                Map<String, Object> motionRange,
                @Size(max = 32) String studentNo) {

            /** 兼容旧构造(仅 studentId,无 studentNo) */
            public ClipItem(UUID studentId, Integer clipIndex, String actionType,
                            BigDecimal startMs, BigDecimal endMs, BigDecimal releaseMs,
                            String anchorCamera, String zoneId, List<Map<String, Object>> phases,
                            Boolean shotMade, Map<String, Object> score, String motionUri,
                            Map<String, Object> motionRange) {
                this(studentId, clipIndex, actionType, startMs, endMs, releaseMs, anchorCamera,
                        zoneId, phases, shotMade, score, motionUri, motionRange, null);
            }
        }
    }
}

