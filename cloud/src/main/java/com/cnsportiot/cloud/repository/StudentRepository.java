package com.cnsportiot.cloud.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cnsportiot.cloud.domain.entity.Student;

/** 学生档案仓储 */
public interface StudentRepository extends JpaRepository<Student, UUID> {

    Optional<Student> findByAccountId(UUID accountId);

    Optional<Student> findByStudentNo(String studentNo);

    boolean existsByStudentNo(String studentNo);

    interface StudentBrief {
        UUID getStudentId();
        String getStudentNo();
        String getDisplayName();
        String getGradeBand();
        com.cnsportiot.contracts.enums.DominantHand getDominantHand();
        boolean isGalleryReady();
    }

    interface StudentDetail {
        UUID getStudentId();
        String getStudentNo();
        String getDisplayName();
        com.cnsportiot.contracts.enums.DominantHand getDominantHand();
        java.math.BigDecimal getHeightCm();
        java.math.BigDecimal getLegLengthCm();
        String getGradeBand();
        UUID getGalleryId();
        Integer getGalleryVersion();
        String getGalleryStatus();
        Integer getGallerySampleCount();
        java.time.OffsetDateTime getGalleryEnrolledAt();
    }

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

    @Query(value = """
            SELECT student_id    AS studentId,
                   student_no    AS studentNo,
                   display_name  AS displayName,
                   grade_band    AS gradeBand,
                   dominant_hand AS dominantHand,
                   gallery_ready AS galleryReady
            FROM v_student_brief
            WHERE (:kw IS NULL OR student_no LIKE concat(:kw, '%') OR display_name LIKE concat('%', :kw, '%'))
            ORDER BY student_no
            """,
            countQuery = """
            SELECT count(*)
            FROM v_student_brief
            WHERE (:kw IS NULL OR student_no LIKE concat(:kw, '%') OR display_name LIKE concat('%', :kw, '%'))
            """,
            nativeQuery = true)
    org.springframework.data.domain.Page<StudentBrief> findBriefsByKeyword(@Param("kw") String keyword, org.springframework.data.domain.Pageable pageable);

    @Query(value = """
            SELECT s.id              AS studentId,
                   s.student_no      AS studentNo,
                   a.display_name    AS displayName,
                   s.dominant_hand   AS dominantHand,
                   s.height_cm       AS heightCm,
                   s.leg_length_cm   AS legLengthCm,
                   s.grade_band      AS gradeBand,
                   g.id              AS galleryId,
                   g.version         AS galleryVersion,
                   g.status          AS galleryStatus,
                   g.sample_count    AS gallerySampleCount,
                   g.enrolled_at     AS galleryEnrolledAt
            FROM student s
                     JOIN account a ON a.id = s.account_id
                     LEFT JOIN reid_gallery g ON g.id = s.active_gallery_id
            WHERE s.id = :studentId
              AND a.status = 'ACTIVE'
            """,
            nativeQuery = true)
    java.util.Optional<StudentDetail> findActiveDetailByStudentId(@Param("studentId") UUID studentId);
}
