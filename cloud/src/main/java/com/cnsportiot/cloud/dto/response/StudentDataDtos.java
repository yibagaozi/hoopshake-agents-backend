package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.contracts.enums.FeedbackSeverity;
import com.cnsportiot.contracts.enums.SessionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 学生训练数据响应 DTO */
public final class StudentDataDtos {
    private StudentDataDtos() {}

    /** 4.1 训练概览 */
    public record TrainingOverviewResponse(
            UUID studentId,
            String displayName,
            long totalSessions,
            long totalClips,
            OffsetDateTime lastSessionAt,
            WeeklyStats weekly,                 // 本周口径
            FocusCheckpoint focusCheckpoint,    // 本阶段重点
            List<ActionTypeStat> actionTypeStats,
            List<SessionBriefResponse> recentSessions) {}

    /** 本周训练 / 出手 / 命中率 */
    public record WeeklyStats(long sessions, long clips, BigDecimal madeRate) {}

    /** 4.2 训练课列表 */
    public record SessionBriefResponse(
            UUID sessionId,
            UUID lessonId,
            String lessonTitle,
            SessionStatus status,
            OffsetDateTime recordedAt,
            long clipCount,
            String keyImprovementLabel) {}

    /** 4.3 训练课详情 */
    public record SessionDetailResponse(
            UUID sessionId,
            UUID lessonId,
            String lessonTitle,
            SessionStatus status,
            OffsetDateTime recordedAt,
            long clipCount,
            List<SessionAggregateResponse> aggregates,
            long feedbackCount,
            boolean reportReady) {}

    public record SessionAggregateResponse(String actionType, Map<String, Object> stats) {}

    /** 4.4 列表 / 4.5 完整视图 */
    public record ActionClipResponse(
            UUID clipId,
            UUID sessionId,
            int clipIndex,
            String actionType,
            BigDecimal startMs,
            BigDecimal endMs,
            BigDecimal releaseMs,
            String anchorCamera,
            String zoneId,
            Boolean shotMade,
            Map<String, Object> score,
            List<Map<String, Object>> phases,      // 仅完整视图
            Map<String, Object> motionRange,       // 仅完整视图
            String motionDataUrl) {}               // 仅完整视图,MinIO 预签名

    /** 4.6 即时反馈 */
    public record InstantFeedbackResponse(
            UUID feedbackId,
            UUID clipId,
            OffsetDateTime occurredAt,
            BigDecimal timestampMs,
            String actionType,
            String checkpointId,
            FeedbackSeverity severity,
            String cueText,
            Map<String, Object> measured,
            BigDecimal confidence,
            String sourceCamera) {}

    /** 4.7 进步趋势 */
    public record ProgressTrendResponse(String actionType, String metric, List<TrendPoint> points) {}

    public record OverviewResponse(
            UUID studentId, String displayName, int totalSessions, int totalClips,
            OffsetDateTime lastSessionAt, Weekly weekly, FocusCheckpoint focusCheckpoint,
            List<ActionTypeStat> actionTypeStats, List<SessionBrief> recentSessions) {}

    public record Weekly(int sessions, int clips, Double madeRate) {}

    /**
     * 本周重点检查点。
     *
     * @param checkpointId   检查点 id,前端可据此查 /api/meta/vocabulary 自行兜底
     * @param label          中文名;未知时为 null,不会回填成 id
     * @param progress       达标率,取值 0~1(不是百分数)
     * @param improvementPct 相对上一次训练的变化(百分点)。null = 无上次数据、不可比;
     *                       0 = 确实持平。前端不要把 null 渲染成 "+0%"
     */
    public record FocusCheckpoint(String checkpointId, String label, double progress, Double improvementPct) {}

    public record ActionTypeStat(String actionType, int clipCount, Double madeRate) {}

    public record SessionBrief(
            UUID sessionId, UUID lessonId, String lessonTitle, String status,
            OffsetDateTime recordedAt, int clipCount, String keyImprovementLabel) {}

    public record SessionDetail(
            UUID sessionId, UUID lessonId, String lessonTitle, String status, OffsetDateTime recordedAt,
            int clipCount, List<AggItem> aggregates, int feedbackCount, boolean reportReady) {}

    public record AggItem(String actionType, Object stats) {}

    public record ClipBrief(
            UUID clipId, UUID sessionId, int clipIndex, String actionType,
            BigDecimal startMs, BigDecimal endMs, BigDecimal releaseMs,
            String anchorCamera, String zoneId, Boolean shotMade, Object score) {}

    public record ClipDetail(
            UUID clipId, UUID sessionId, int clipIndex, String actionType,
            BigDecimal startMs, BigDecimal endMs, BigDecimal releaseMs,
            String anchorCamera, String zoneId, Boolean shotMade, Object score,
            Object phases, Object motionRange, String motionDataUrl) {}

    public record FeedbackItem(
            UUID feedbackId, UUID clipId, OffsetDateTime occurredAt, BigDecimal timestampMs,
            String actionType, String checkpointId, String severity, String cueText,
            Object measured, BigDecimal confidence, String sourceCamera) {}

    public record TrendResponse(String actionType, String metric, List<TrendPoint> points) {}

    public record TrendPoint(UUID sessionId, OffsetDateTime recordedAt, double value) {}

    public record ProfileResponse(
            UUID studentId, String studentNo, String displayName, String dominantHand,
            BigDecimal heightCm, BigDecimal legLengthCm, String gradeBand) {}
}

