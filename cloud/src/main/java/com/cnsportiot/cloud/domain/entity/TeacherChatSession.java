package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * 教师分析对话会话(A2)。**只挂 teacher_id,不绑学生**——分析对象由对话中经工具动态解析,
 * 归属由 {@code TeacherScopeGuardHook} 逐次校验。消息复用现有 {@code chat_message}
 * (其 {@code chat_session_id} 无外键,指向本会话 id 即可),学生侧 chat_session/chat_message 不受影响
 */
@Entity
@Table(name = "teacher_chat_session")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TeacherChatSession extends AuditableEntity {

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    @Column(name = "title", length = 64)
    private String title;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    public static TeacherChatSession create(UUID teacherId, String title) {
        return TeacherChatSession.builder().teacherId(teacherId).title(title).deleted(false).build();
    }
}
