package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** 对话会话:一个学生与某个 Agent 的一轮持续对话,承载消息列表。 */
@Entity
@Table(name = "chat_session", indexes = {
        // 会话列表按 updated_at 倒序
        @Index(name = "idx_chat_session_student", columnList = "student_id, updated_at")
})
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ChatSession extends AuditableEntity {

    /** 会话归属学生(数据主体,固定为 access token 中的 studentId) */
    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    /** 可选锚定课程,缺省表示通用对话 */
    @Column(name = "lesson_id")
    private UUID lessonId;

    /** Agent 类型:skill_coach(学生技能教练)等,为将来多 Agent 预留。 */
    @Column(name = "agent_type", nullable = false, length = 32)
    @Builder.Default
    private String agentType = "skill_coach";

    /** 会话标题,≤64;缺省由服务端按首问生成 */
    @Column(name = "title", length = 64)
    private String title;

    /** 软删标记 */
    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    /** 便捷工厂:新建一个学生技能教练会话 */
    public static ChatSession create(UUID studentId, UUID lessonId, String title) {
        return ChatSession.builder()
                .studentId(studentId)
                .lessonId(lessonId)
                .title(title)
                .build();
    }
}

