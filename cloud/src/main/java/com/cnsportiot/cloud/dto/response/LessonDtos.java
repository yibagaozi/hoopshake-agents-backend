package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.cloud.domain.enums.FeedbackSeverity;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.domain.enums.SessionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 课程管理与课堂实况响应 DTO */
public final class LessonDtos {
    private LessonDtos() {}

    /** 5.1/5.2/5.3 课程 */
    public record LessonResponse(
            UUID lessonId,
            UUID teacherId,
            String title,
            String classCode,
            List<String> actionTypes,
            List<String> enabledCheckpoints,
            String zoneConfigRef,
            String gradeBand,
            OffsetDateTime scheduledAt,
            Integer durationMinutes,
            LessonStatus status,
            int enrolledCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    /** 5.6/5.7 参课名单项 */
    public record EnrollmentResponse(
            UUID studentId,
            String studentNo,
            String displayName,
            boolean galleryReady,
            OffsetDateTime enrolledAt) {}

    /** 5.9/5.10 课堂实况 */
    public record LessonLiveResponse(
            UUID lessonId,
            LessonStatus lessonStatus,
            ActiveSession activeSession,
            int presentStudentCount,
            Map<String, Long> clipCountByAction,
            List<LiveFeedbackItem> recentFeedback,
            List<SafetyAlertResponse> safetyAlerts) {}

    public record ActiveSession(UUID sessionId, SessionStatus status, OffsetDateTime recordedAt) {}

    public record LiveFeedbackItem(
            UUID feedbackId,
            UUID studentId,
            String displayName,
            String actionType,
            String checkpointId,
            FeedbackSeverity severity,
            String cueText,
            OffsetDateTime occurredAt) {}

    /** 安全提醒 */
    public record SafetyAlertResponse(
            UUID feedbackId,
            UUID studentId,
            String displayName,
            String actionType,
            String checkpointId,
            FeedbackSeverity severity,
            String message,
            OffsetDateTime occurredAt) {}
}
