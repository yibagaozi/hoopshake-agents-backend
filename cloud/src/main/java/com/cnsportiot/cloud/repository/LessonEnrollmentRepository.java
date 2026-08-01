package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.LessonEnrollment;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**课程报名记录仓储*/
public interface LessonEnrollmentRepository extends JpaRepository<LessonEnrollment, UUID> {

    /** 查询课程下全部报名记录 */
    List<LessonEnrollment> findByLessonId(UUID lessonId);

    /** 根据课程ID+学生ID查询报名记录 */
    Optional<LessonEnrollment> findByLessonIdAndStudentId(UUID lessonId, UUID studentId);

    /** 判断学生是否已经报名该课程 */
    boolean existsByLessonIdAndStudentId(UUID lessonId, UUID studentId);

    /** 统计单课程报名人数 */
    @Query("SELECT COUNT(e) FROM LessonEnrollment e WHERE e.lessonId = :lessonId")
    long countByLessonId(@Param("lessonId") UUID lessonId);

    /** 名单视图 */
    interface EnrollmentView {
        UUID getStudentId();
        String getStudentNo();
        String getDisplayName();
        AccountStatus getAccountStatus();
        boolean getGalleryReady();
        OffsetDateTime getEnrolledAt();
    }

    @Query("""
            SELECT s.id            AS studentId,
                   s.studentNo     AS studentNo,
                   a.displayName   AS displayName,
                   a.status        AS accountStatus,
                   (CASE WHEN s.activeGalleryId IS NOT NULL THEN TRUE ELSE FALSE END) AS galleryReady,
                   e.createdAt     AS enrolledAt
            FROM LessonEnrollment e
                 JOIN Student s ON s.id = e.studentId
                 JOIN Account a ON a.id = s.accountId
            WHERE e.lessonId = :lessonId
            ORDER BY s.studentNo
            """)
    List<EnrollmentView> findEnrollmentView(@Param("lessonId") UUID lessonId);

    /** 预检:只取 id 集合判断"已报名" */
    @Query("SELECT e.studentId FROM LessonEnrollment e WHERE e.lessonId = :lessonId")
    List<UUID> findStudentIdsByLessonId(@Param("lessonId") UUID lessonId);

    /** 报名
     *
     * @return 1 = 本次新报名,0 = 之前已报名
     */
    @Modifying
    @Query(value = """
            INSERT INTO lesson_enrollment (id, lesson_id, student_id, created_at)
            VALUES (gen_random_uuid(), :lessonId, :studentId, now())
            ON CONFLICT (lesson_id, student_id) DO NOTHING
            """, nativeQuery = true)
    int enrollIfAbsent(@Param("lessonId") UUID lessonId, @Param("studentId") UUID studentId);

    /** 取消报名 */
    @Modifying
    @Query("DELETE FROM LessonEnrollment e WHERE e.lessonId = :lessonId AND e.studentId = :studentId")
    int deleteByLessonIdAndStudentId(@Param("lessonId") UUID lessonId,
                                     @Param("studentId") UUID studentId);

    /** 批量统计报名人数 */
    @Query("SELECT e.lessonId AS lessonId, COUNT(e) AS count FROM LessonEnrollment e " +
           "WHERE e.lessonId IN :lessonIds GROUP BY e.lessonId")
    List<LessonEnrollmentCount> countGroupByLessonId(@Param("lessonIds") Collection<UUID> lessonIds);

    interface LessonEnrollmentCount {
        UUID getLessonId();
        long getCount();
    }
}
