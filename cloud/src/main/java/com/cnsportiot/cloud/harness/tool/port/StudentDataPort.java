package com.cnsportiot.cloud.harness.tool.port;

import java.util.List;
import java.util.UUID;

/**
 * 算法侧数据的隔离边界
 * 所有方法的 {@code studentId} 均为权威身份(由 Hook 注入),实现内必须只返回该学生的数据
 */
public interface StudentDataPort {

    /** 资源归属类型,供越权闸门({@link #owns})判定 */
    enum ResourceType { SESSION, CLIP }

    /**
     * 越权闸门:{@code resourceId} 是否属于 {@code studentId}。
     * StudentScopeGuardHook 在执行前对入参里的会话/片段 id 调用本方法,不属于即拒绝(DATA_SCOPE_DENIED)
     */
    boolean owns(UUID studentId, ResourceType type, String resourceId);

    /** 最近训练片段 */
    List<ClipSummary> recentClips(UUID studentId, int limit);

    /** 单次训练小结;{@code trainingSessionId} 为空取最近一次 */
    SessionSummary sessionSummary(UUID studentId, UUID trainingSessionId);

    /** 即时反馈流水;{@code trainingSessionId} 为空取最近一次 */
    List<FeedbackEntry> instantFeedbackLog(UUID studentId, UUID trainingSessionId);

    /** 某指标的进步趋势 */
    ProgressTrend progressTrend(UUID studentId, String metric, int weeks);

    /** 某动作的要点/常见错误 */
    ActionDetail actionDetail(UUID studentId, String actionKey);

    // 结论层 DTO

    record ClipSummary(
            UUID trainingSessionId, String action, String recordedAt,
            int madeShots, int totalShots, String topIssue, String sourceNote) {}

    record SessionSummary(
            UUID trainingSessionId, String action, String recordedAt,
            int madeShots, int totalShots, List<String> keyFindings, String coachNote, String sourceNote) {}

    record FeedbackEntry(String at, String cue, String detail, String severity, String sourceNote) {}

    record ProgressTrend(
            String metric, String period, List<TrendPoint> points, String summary, String sourceNote) {
        public record TrendPoint(String label, double value) {}
    }

    record ActionDetail(
            String actionKey, String title, List<String> keyPoints,
            List<String> commonMistakes, String sourceNote) {}
}

