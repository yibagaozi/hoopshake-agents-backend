package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.cloud.domain.enums.SessionStatus;
import com.cnsportiot.cloud.dto.response.LessonDtos.SafetyAlertResponse;
import com.cnsportiot.cloud.dto.response.StudentDataDtos.TrendPoint;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 课末汇总响应 DTO */
public final class SummaryDtos {
    private SummaryDtos() {}

    public record ClassSummaryResponse(
            UUID sessionId,
            UUID lessonId,
            String lessonTitle,
            String classCode,
            SessionStatus sessionStatus,
            OffsetDateTime recordedAt,
            Integer durationMinutes,
            Attendance attendance,                              // 出勤
            long totalClips,                                    // 总动作数
            BigDecimal avgMadeRate,                             // 平均命中率
            int safetyAlertCount,                              // 安全提醒数
            List<CheckpointDistributionItem> checkpointDistribution, // 常见问题分布
            List<SafetyAlertResponse> safetyAlerts,            // 安全提醒卡片
            List<ClassSummaryStudentRow> students,             // 学生表
            String classSummaryMarkdown) {}

    public record Attendance(int present, int enrolled) {}

    public record CheckpointDistributionItem(String checkpointId, String label, long count, boolean safety) {}

    public record ClassSummaryStudentRow(
            UUID studentId,
            String displayName,
            long clipCount,
            String keyCheckpointId,   // 主要改进点
            String keyLabel,
            List<TrendPoint> trend,   // sparkline 数据点
            boolean recognized) {}    // 识别 已识别/待确认
}

