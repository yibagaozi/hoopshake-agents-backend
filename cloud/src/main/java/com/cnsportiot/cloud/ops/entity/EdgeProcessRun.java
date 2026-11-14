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
@Table(name = "edge_process_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_edge_process_run_run", columnNames = "run_id"),
        indexes = {
                @Index(name = "idx_epr_edge_time", columnList = "edge_id,started_at"),
                @Index(name = "idx_epr_type_time", columnList = "process_type,started_at"),
                @Index(name = "idx_epr_status_time", columnList = "status,started_at")
        })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EdgeProcessRun extends AuditableEntity {

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "edge_id", nullable = false, length = 64)
    private String edgeId;

    @Column(name = "process_type", nullable = false, length = 32)
    private String processType;

    @Column(name = "process_name", nullable = false, length = 64)
    private String processName;

    @Column(columnDefinition = "text")
    private String command;

    @Column(name = "work_dir", columnDefinition = "text")
    private String workDir;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column
    private Long pid;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "timeout_ms")
    private Long timeoutMs;

    @Column(name = "restart_count")
    private Integer restartCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> attrs;
}
