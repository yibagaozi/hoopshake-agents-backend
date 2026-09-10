package com.cnsportiot.cloud.ops.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 场边设备当前态(边缘盒子心跳 upsert)。只保留"最近一拍",非时序流水,规模 = 设备数
 * 运维侧据 {@link #lastSeenAt} 与阈值派生在线/离线(读时计算,不落库)
 */
@Entity
@Table(name = "edge_device", indexes = {
        @Index(name = "idx_edge_device_last_seen", columnList = "last_seen_at")
})
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EdgeDevice extends AuditableEntity {

    /** 设备唯一标识(盒子序列号/MAC),幂等 upsert 键 */
    @Column(name = "device_id", nullable = false, unique = true, length = 64)
    private String deviceId;

    @Column(name = "name", length = 64)
    private String name;

    /** 场地/球馆标识 */
    @Column(name = "court_id", length = 64)
    private String courtId;

    /** 设备自报状态(RUNNING/IDLE/ERROR…… 自由字符串)。派生健康态另算 */
    @Column(name = "reported_status", length = 24)
    private String reportedStatus;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "firmware", length = 32)
    private String firmware;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** 弹性指标:cpu_pct/mem_pct/disk_free_mb/temp_c/fps/uptime_seconds/camera_count/pipeline_lag_ms…… */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", columnDefinition = "jsonb")
    private Map<String, Object> metrics;

    @Column(name = "last_error", length = 255)
    private String lastError;

    /** 最近一次心跳时间——派生在线态的唯一依据 */
    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;
}
