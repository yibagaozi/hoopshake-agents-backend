package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import com.cnsportiot.cloud.domain.enums.HelpRequestStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 学生「请求教师协助」工单:多轮对话未解决疑惑时,由学生(经 Agent 判定后展示的按钮)发起,
 * 落库后教师端列表拉取处理。教师归属通过学生所在课程(lesson.teacher_id)确定
 */
@Entity
@Table(name = "help_request")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class HelpRequest extends AuditableEntity {

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    /** 触发协助的课程(可空:无课独立对话) */
    @Column(name = "lesson_id")
    private UUID lessonId;

    /** 触发协助的对话会话(可空) */
    @Column(name = "session_id")
    private UUID sessionId;

    /** 学生未解决的问题(Agent 归纳或学生补充) */
    @Column(nullable = false, columnDefinition = "text")
    private String question;

    /** 上下文快照:近几轮摘要、意图、轮次等,供教师快速了解 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> context;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private HelpRequestStatus status = HelpRequestStatus.PENDING;

    /** 教师答复(可空) */
    @Column(name = "teacher_reply", columnDefinition = "text")
    private String teacherReply;

    /** 处理教师的 account id(可空) */
    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(name = "handled_at")
    private OffsetDateTime handledAt;
}

