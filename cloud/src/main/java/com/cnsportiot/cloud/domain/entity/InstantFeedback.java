package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.BaseEntity;
import com.cnsportiot.contracts.enums.FeedbackSeverity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 边缘即时反馈(perception-core 课中实时产出,基于单机位 2D 规则判定)。
 * clip_id 可空:课后按 timestamp_ms 软对齐回填到对应动作。
 */
@Entity
@Table(name = "instant_feedback")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class InstantFeedback extends BaseEntity {

    @Column(name = "event_id", unique = true, length = 128)
    private String eventId;

    @Column(name = "session_id")
    private UUID sessionId;   // 可空:实时时 session 可能尚未建全

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    /** 事后对齐回填:指向对应动作 */
    @Column(name = "clip_id")
    private UUID clipId;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "timestamp_ms", precision = 12, scale = 1)
    private BigDecimal timestampMs;

    @Column(name = "action_type", length = 24)
    private String actionType;

    @Column(name = "checkpoint_id", length = 64)
    private String checkpointId;   // 命中的检查点

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private FeedbackSeverity severity;

    @Column(name = "cue_text", length = 255)
    private String cueText;   // 实际推送的话术

    /** 该检查点的 2D 测量值 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> measured;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "source_camera", length = 16)
    private String sourceCamera;
}
