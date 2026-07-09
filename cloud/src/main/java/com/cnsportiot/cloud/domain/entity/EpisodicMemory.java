package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 长期记忆。embedding vector(1024) 同 CheckpointKnowledge,由 Spring AI VectorStore 管理。
 */
@Entity
@Table(name = "episodic_memory")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EpisodicMemory extends BaseEntity {

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Transient
    private float[] embedding;   // 见 CheckpointKnowledge 注释

    @Column(precision = 4, scale = 2)
    private BigDecimal importance;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;
}
