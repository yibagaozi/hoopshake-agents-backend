package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.BaseEntity;
import com.cnsportiot.cloud.domain.enums.GalleryStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ReID 特征版本表。特征本体在中心存储(MinIO),此处仅存版本与引用,支持跨场地共享与注销级联。
 */
@Entity
@Table(name = "reid_gallery")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ReidGallery extends BaseEntity {

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    /** 中心存储引用(权威副本),如 minio://reid/{student_id}/v{n}/ */
    @Column(name = "storage_uri", nullable = false, length = 512)
    private String storageUri;

    @Column(name = "face_model", length = 64)
    private String faceModel;

    @Column(name = "body_model", length = 64)
    private String bodyModel;

    @Column(name = "face_dim")
    private Integer faceDim;

    @Column(name = "body_dim")
    private Integer bodyDim;

    @Column(name = "sample_count")
    private Integer sampleCount;

    @Column(name = "enrolled_camera", length = 16)
    private String enrolledCamera;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private GalleryStatus status = GalleryStatus.ACTIVE;

    @Column(name = "enrolled_at")
    private OffsetDateTime enrolledAt;
}
