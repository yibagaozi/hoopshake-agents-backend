package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "lesson")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Lesson extends AuditableEntity {

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    @Column(nullable = false, length = 128)
    private String title;

    /** 本节练的动作,如 ["shot","layup"]。用 Hibernate 原生 JSON 映射 List。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_types", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> actionTypes = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enabled_checkpoints", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> enabledCheckpoints = List.of();

    @Column(name = "zone_config_ref", length = 255)
    private String zoneConfigRef;

    @Column(name = "grade_band", length = 16)
    private String gradeBand;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(name = "class_code", length = 64)
    private String classCode;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private LessonStatus status = LessonStatus.PLANNED;
}
