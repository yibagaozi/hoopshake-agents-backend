package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.cloud.domain.enums.HelpRequestStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 协助工单响应 DTO */
public final class HelpRequestDtos {
    private HelpRequestDtos() {}

    /**
     * 学生视角:自己的工单。
     *
     * @param status      PENDING(待查看) / VIEWED(教师已看) / RESOLVED(已答复) / DISMISSED(已关闭)
     * @param teacherName 处理教师姓名;未处理时为 null
     * @param teacherReply 教师回复正文;未回复时为 null
     */
    public record HelpRequestResponse(
            UUID id,
            UUID sessionId,
            String question,
            HelpRequestStatus status,
            String teacherName,
            String teacherReply,
            OffsetDateTime createdAt,
            OffsetDateTime handledAt) {}

    /** 教师视角:带学生学号/姓名的工单项 */
    public record TeacherHelpRequestItem(
            UUID id,
            UUID studentId,
            String studentNo,
            String displayName,
            UUID lessonId,
            UUID sessionId,
            String question,
            HelpRequestStatus status,
            String teacherReply,
            OffsetDateTime createdAt,
            OffsetDateTime handledAt) {}
}
