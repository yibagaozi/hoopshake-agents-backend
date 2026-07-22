package com.cnsportiot.cloud.dto.request;

import com.cnsportiot.cloud.domain.enums.LessonStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 课程管理请求 DTO */
public final class LessonRequests {
    private LessonRequests() {}

    /** 5.1 创建课程 */
    public record CreateLessonRequest(
            @NotBlank @Size(max = 128) String title,
            @Size(max = 32) String classCode,
            @NotEmpty(message = "训练动作不能为空") List<String> actionTypes,
            List<String> enabledCheckpoints,
            @Size(max = 255) String zoneConfigRef,
            @Size(max = 16) String gradeBand,
            OffsetDateTime scheduledAt,
            @Min(10) @Max(240) Integer durationMinutes) {}

    /** 5.4 更新课程(字段均可选; 仅 PLANNED 可改) */
    public record UpdateLessonRequest(
            @Size(max = 128) String title,
            @Size(max = 32) String classCode,
            List<String> actionTypes,
            List<String> enabledCheckpoints,
            @Size(max = 255) String zoneConfigRef,
            @Size(max = 16) String gradeBand,
            OffsetDateTime scheduledAt,
            @Min(10) @Max(240) Integer durationMinutes) {}

    /** 5.5 状态推进 */
    public record UpdateLessonStatusRequest(
            @NotNull LessonStatus status) {}

    /** 5.7 批量报名 */
    public record EnrollStudentsRequest(
            @NotEmpty(message = "学生列表不能为空") List<UUID> studentIds) {}
}

