package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.LessonEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**课程报名记录仓储*/
public interface LessonEnrollmentRepository extends JpaRepository<LessonEnrollment, UUID> {

    /**
     * 查询课程下全部报名记录
     * @param lessonId 课程ID
     */
    List<LessonEnrollment> findByLessonId(UUID lessonId);

    /**根据课程ID+学生ID查询报名记录*/
    Optional<LessonEnrollment> findByLessonIdAndStudentId(UUID lessonId, UUID studentId);

    /**判断学生是否已经报名该课程*/
    boolean existsByLessonIdAndStudentId(UUID lessonId, UUID studentId);

    /**删除指定课程学生报名记录（§5.8 取消报名）*/
    void deleteByLessonIdAndStudentId(UUID lessonId, UUID studentId);

    /**统计单课程报名人数*/
    @Query("SELECT COUNT(e) FROM LessonEnrollment e WHERE e.lessonId = :lessonId")
    long countByLessonId(@Param("lessonId") UUID lessonId);

    /**批量统计一批课程各自报名人数*/
    @Query("SELECT e.lessonId, COUNT(e) FROM LessonEnrollment e WHERE e.lessonId IN :lessonIds GROUP BY e.lessonId")
    List<Object[]> countGroupByLessonId(@Param("lessonIds") List<UUID> lessonIds);
}
