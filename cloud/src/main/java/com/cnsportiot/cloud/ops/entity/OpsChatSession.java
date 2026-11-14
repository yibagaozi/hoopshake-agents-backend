package com.cnsportiot.cloud.ops.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * 运维诊断对话会话。只挂 {@code owner_account_id}(ADMIN),
 * 无逐行归属(运维数据是系统级聚合)。消息复用现有 {@code chat_message}(其 {@code chat_session_id} 指向本会话 id),
 * 学生/教师侧会话表不受影响。独立小表换清晰边界,与"运维包自包含"一致
 */
@Entity
@Table(name = "ops_chat_session")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class OpsChatSession extends AuditableEntity {

    @Column(name = "owner_account_id", nullable = false)
    private UUID ownerAccountId;

    @Column(name = "title", length = 64)
    private String title;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    public static OpsChatSession create(UUID ownerAccountId, String title) {
        return OpsChatSession.builder().ownerAccountId(ownerAccountId).title(title).deleted(false).build();
    }
}