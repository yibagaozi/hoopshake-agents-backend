package com.cnsportiot.cloud.harness.tool.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 教师分析取数口(与算法侧解耦)。教师工具经 {@code TeacherScopeGuardHook} 校验归属后,
 * 用已授权的 studentId 列表向本口取聚合结果。真实口径由算法侧实现
 */
public interface TeacherAnalyticsPort {

    /** 单个学生的单次训练指标(trainingSessionId 为空为最近一次) */
    StudentSession studentSession(UUID studentId, UUID trainingSessionId);

    /** 单个学生某指标的历史走势 */
    MetricTrend studentTrend(UUID studentId, String metric, int weeks);

    /** 一组学生某指标的聚合(均值/分布/离群/走势方向) */
    GroupSummary groupSummary(List<UUID> studentIds, String metric, int weeks);

    /** 一组学生的共性问题排名(高频未达检查点 / 安全项 + 改善/恶化) */
    List<CommonIssue> commonIssues(List<UUID> studentIds, int weeks);

    /**
     * 某学生某动作(出手相位)的关节角度均值画像,供与基准运动员比对标准度
     * {@code angles}: 关节名 → 均值角度(度);{@code anglesSource}: triangulated_3d / pseudo3d_fallback / null(未标注,不可跨源比)
     * 无数据时 angles 为空
     */
    ActionAngleProfile actionAngleProfile(UUID studentId, String actionType);

    // 返回结构(结论层,紧凑;不含算法原始逐帧数据)

    record StudentSession(
            UUID studentId,
            UUID trainingSessionId,
            String recordedAt,
            double checkpointPassRate,
            int clips,
            List<CheckpointResult> checkpoints,
            List<String> topIssues,
            String sourceNote) {}

    record CheckpointResult(String checkpoint, double passRate, int attempts) {}

    record MetricTrend(
            UUID studentId,
            String metric,
            List<TrendPoint> points,
            String direction,          // improving / worsening / flat
            String sourceNote) {}

    record TrendPoint(String label, double value) {}

    record GroupSummary(
            int studentCount,
            String metric,
            double mean,
            double median,
            double min,
            double max,
            List<StudentValue> perStudent,
            List<StudentValue> bottomOutliers,   // 最需关注的后进
            String trend,                        // improving / worsening / flat
            String sourceNote) {}

    record StudentValue(UUID studentId, double value) {}

    record CommonIssue(
            String issue,              // 如 checkpoint:release_timing 未达
            int affectedStudents,
            double frequency,          // 0..1
            String trend,              // improving / worsening / flat
            String sourceNote) {}

    /** 某学生某动作的关节角度均值画像 */
    record ActionAngleProfile(
            UUID studentId,
            String actionType,
            Map<String, Double> angles,    // 关节 → 均值角度(度)
            String anglesSource,           // triangulated_3d / pseudo3d_fallback / null
            int sampleCount,
            String sourceNote) {}
}

