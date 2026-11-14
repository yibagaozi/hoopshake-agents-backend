package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 单次 LLM 调用的 token 用量流水(只追加,不更新)。
 *
 * <p>一次对话轮次(或一次 FAST 档补全)落一行:输入/输出分开记,便于按人、按周、按来源审计与计费。
 * {@code estimated=true} 表示提供方未回传 usage,用 {@link com.cnsportiot.cloud.harness.llm.TokenEstimator}
 * 估算而来——审计口径上要能区分“实测”与“估算”,不能混为一谈。
 *
 * <p>周用量上限按 {@code account_id + occurred_at} 聚合判定,故这两列建了联合索引。
 */
@Entity
@Table(name = "token_usage_record",
        indexes = {
                @Index(name = "idx_tur_account_time", columnList = "account_id,occurred_at"),
                @Index(name = "idx_tur_time", columnList = "occurred_at"),
                @Index(name = "idx_tur_source_time", columnList = "source,occurred_at")
        })
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TokenUsageRecord extends BaseEntity {

    /** 用量归属账号(计入其周配额)。 */
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** 账号角色快照(STUDENT/TEACHER/ADMIN):配额按角色分档,留档便于事后核对。 */
    @Column(name = "account_role", length = 16)
    private String accountRole;

    /** 调用来源:STUDENT_CHAT / TEACHER_CHAT / OPS_CHAT / ROUTER 等,见 {@link com.cnsportiot.cloud.harness.usage.UsageSource}。 */
    @Column(nullable = false, length = 32)
    private String source;

    /** 实际使用的模型名(优先取提供方回传的 model)。 */
    @Column(length = 64)
    private String model;

    /** 档位 FAST/STANDARD/ADVANCED。 */
    @Column(length = 16)
    private String tier;

    /** 输入(prompt)token。 */
    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    /** 输出(completion)token。 */
    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    /** 合计,冗余一列避免聚合时逐行相加。 */
    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    /** true=提供方未回传 usage,本行为估算值。 */
    @Column(nullable = false)
    private boolean estimated;

    /** 关联会话(对话/运维会话),可空。 */
    @Column(name = "session_id")
    private UUID sessionId;

    /** 结束原因:stop / interrupted / error,便于区分“白花的”用量。 */
    @Column(name = "finish_reason", length = 32)
    private String finishReason;

    /** 发生时刻(周配额按此列聚合,不用 created_at,便于补录历史)。 */
    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;
}
