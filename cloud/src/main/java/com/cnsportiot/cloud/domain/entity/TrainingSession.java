package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import com.cnsportiot.cloud.domain.enums.SessionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 训练 Session:算法 pipeline 的组织主线。
 * status 是算法侧状态机;逐帧数据不入库,motion_export_uri 指向 MinIO。
 */
@Entity
@Table(name = "training_session")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TrainingSession extends AuditableEntity {

    @Column(name = "lesson_id")
    private UUID lessonId;   // 可空:支持无课的独立录制

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private SessionStatus status = SessionStatus.CREATED;

    /** SessionOutput 的 coordinate_system:{space,unit,origin,axes} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "coordinate_system", columnDefinition = "jsonb")
    private Map<String, Object> coordinateSystem;

    @Column(name = "camera_config_ref", length = 255)
    private String cameraConfigRef;

    @Column(name = "data_dir", length = 255)
    private String dataDir;

    /** 整 session 逐帧导出 motion.jsonl(MinIO) */
    @Column(name = "motion_export_uri", length = 512)
    private String motionExportUri;

    @Column(name = "recorded_at")
    private OffsetDateTime recordedAt;

    @Column(name = "generated_at")
    private OffsetDateTime generatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
