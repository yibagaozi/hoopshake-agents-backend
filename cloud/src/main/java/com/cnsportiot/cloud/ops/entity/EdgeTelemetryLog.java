package com.cnsportiot.cloud.ops.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "edge_telemetry_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_edge_telemetry_log_event", columnNames = "event_id"),
        indexes = {
                @Index(name = "idx_etl_edge_time", columnList = "edge_id,occurred_at"),
                @Index(name = "idx_etl_level_time", columnList = "level,occurred_at"),
                @Index(name = "idx_etl_source_time", columnList = "source,occurred_at"),
                @Index(name = "idx_etl_session_time", columnList = "session_id,occurred_at"),
                @Index(name = "idx_etl_run_time", columnList = "run_id,occurred_at")
        })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EdgeTelemetryLog extends AuditableEntity {

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "edge_id", nullable = false, length = 64)
    private String edgeId;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(nullable = false, length = 8)
    private String level;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(length = 255)
    private String logger;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "error_class", length = 255)
    private String errorClass;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "text")
    private String stackTrace;

    @Column(name = "process_type", length = 32)
    private String processType;

    @Column(name = "process_name", length = 64)
    private String processName;

    @Column(name = "run_id")
    private UUID runId;

    @Column
    private Long pid;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "lesson_id")
    private UUID lessonId;

    @Column(name = "camera_id", length = 32)
    private String cameraId;

    @Column(length = 64)
    private String operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> attrs;
}
