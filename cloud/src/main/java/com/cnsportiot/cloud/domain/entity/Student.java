package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import com.cnsportiot.cloud.domain.enums.DominantHand;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "student")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Student extends AuditableEntity {

    @Column(name = "account_id", nullable = false, unique = true)
    private UUID accountId;

    @Column(name = "student_no", nullable = false, unique = true, length = 32)
    private String studentNo;   // 学号

    @Enumerated(EnumType.STRING)
    @Column(name = "dominant_hand", length = 8)
    private DominantHand dominantHand;   // 主看侧路由用

    @Column(name = "height_cm", precision = 5, scale = 1)
    private BigDecimal heightCm;         // 个人基线

    @Column(name = "leg_length_cm", precision = 5, scale = 1)
    private BigDecimal legLengthCm;

    @Column(name = "grade_band", length = 16)
    private String gradeBand;

    /** 当前生效的 gallery 版本(reid_gallery.id);特征本体在 MinIO */
    @Column(name = "active_gallery_id")
    private UUID activeGalleryId;

    public static Student create(UUID accountId, String studentNo) {
        return Student.builder()
            .accountId(accountId).studentNo(studentNo).build();
    }
}