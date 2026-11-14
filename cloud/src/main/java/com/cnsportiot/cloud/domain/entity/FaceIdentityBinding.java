package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "face_identity_binding", uniqueConstraints = {
        @UniqueConstraint(name = "uk_face_identity_binding_student", columnNames = "student_id"),
        @UniqueConstraint(name = "uk_face_identity_binding_global", columnNames = "global_id")
})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FaceIdentityBinding extends AuditableEntity {

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "student_no", nullable = false, length = 32)
    private String studentNo;

    @Column(name = "global_id", nullable = false, length = 64)
    private String globalId;

    @Column(name = "local_id", nullable = false, length = 64)
    private String localId;

    @Column(name = "lesson_id")
    private UUID lessonId;

    @Column(name = "edge_id", length = 64)
    private String edgeId;

    @Column(name = "bound_at", nullable = false)
    private OffsetDateTime boundAt;
}
