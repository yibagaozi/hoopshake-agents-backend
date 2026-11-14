package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.BaseEntity;
import com.cnsportiot.cloud.domain.enums.MessageRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/** 对话消息 */
@Entity
@Table(name = "chat_message", indexes = {
        // 会话消息按时间正序分页
        @Index(name = "idx_chat_message_session", columnList = "chat_session_id, created_at")
})
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ChatMessage extends BaseEntity {

    @Column(name = "chat_session_id", nullable = false)
    private UUID chatSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "token_usage")
    private Integer tokenUsage;

    /** 复盘用元数据(路由决策 / 命中的 RAG 片段 / 工具轨迹 / 收尾原因) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;
}
