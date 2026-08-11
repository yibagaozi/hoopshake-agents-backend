package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.contracts.enums.DominantHand;
import com.cnsportiot.contracts.enums.GalleryStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 学生档案仓储 */
public interface StudentRepository extends JpaRepository<Student, UUID> {

    Optional<Student> findByAccountId(UUID accountId);

    Optional<Student> findByStudentNo(String studentNo);

    /** 按学号查已存在的学生 */
    interface StudentRef {
        UUID getStudentId();
        String getStudentNo();
        String getDisplayName();
    }

    @Query("""
            SELECT s.id          AS studentId,
                   s.studentNo   AS studentNo,
                   a.displayName AS displayName
            FROM Student s
                 JOIN Account a ON a.id = s.accountId
            WHERE s.studentNo IN :studentNos
            """)
    List<StudentRef> findRefsByStudentNoIn(@Param("studentNos") Collection<String> studentNos);

    @Query("""
            SELECT s.id          AS studentId,
                   s.studentNo   AS studentNo,
                   a.displayName AS displayName
            FROM Student s
                 JOIN Account a ON a.id = s.accountId
            WHERE s.id IN :studentIds
            """)
    List<StudentRef> findRefsByIdIn(@Param("studentIds") Collection<UUID> studentIds);

    /** 学生详情投影(含 gallery 信息) */
    interface StudentDetail {
        UUID getStudentId();
        String getStudentNo();
        String getDisplayName();
        DominantHand getDominantHand();
        BigDecimal getHeightCm();
        BigDecimal getLegLengthCm();
        String getGradeBand();
        UUID getGalleryId();
        Integer getGalleryVersion();
        GalleryStatus getGalleryStatus();
        Integer getGallerySampleCount();
        OffsetDateTime getGalleryEnrolledAt();
    }

    @Query("""
            SELECT s.id            AS studentId,
                   s.studentNo     AS studentNo,
                   a.displayName   AS displayName,
                   s.dominantHand  AS dominantHand,
                   s.heightCm      AS heightCm,
                   s.legLengthCm   AS legLengthCm,
                   s.gradeBand     AS gradeBand,
                   g.id            AS galleryId,
                   g.version       AS galleryVersion,
                   g.status        AS galleryStatus,
                   g.sampleCount   AS gallerySampleCount,
                   g.enrolledAt    AS galleryEnrolledAt
            FROM Student s
                 JOIN Account a ON a.id = s.accountId
                 LEFT JOIN ReidGallery g ON g.id = s.activeGalleryId
            WHERE s.id = :studentId AND a.status = :status
            """)
    Optional<StudentDetail> findDetailById(@Param("studentId") UUID studentId,
                                           @Param("status") AccountStatus status);
}
