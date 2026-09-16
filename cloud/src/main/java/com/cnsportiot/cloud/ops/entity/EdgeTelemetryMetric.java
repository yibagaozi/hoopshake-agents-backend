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

@Entity
@Table(name = "edge_telemetry_metric",
        uniqueConstraints = @UniqueConstraint(name = "uk_edge_telemetry_metric_event", columnNames = "event_id"),
        indexes = {
                @Index(name = "idx_etm_metric_time", columnList = "metric_name,occurred_at"),
                @Index(name = "idx_etm_edge_time", columnList = "edge_id,occurred_at")
        })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EdgeTelemetryMetric extends AuditableEntity {

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "edge_id", nullable = false, length = 64)
    private String edgeId;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "metric_name", nullable = false, length = 128)
    private String metricName;

    @Column(nullable = false)
    private Double value;

    @Column(length = 16)
    private String unit;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "lesson_id")
    private java.util.UUID lessonId;

    @Column(name = "camera_id", length = 32)
    private String cameraId;

    @Column(name = "process_type", length = 32)
    private String processType;

    @Column(name = "process_name", length = 64)
    private String processName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> dims;
}
