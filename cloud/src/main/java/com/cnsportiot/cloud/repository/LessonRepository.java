package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 课程仓储 */
public interface LessonRepository extends JpaRepository<Lesson, UUID>, JpaSpecificationExecutor<Lesson> {

    /**
     * §5.2 查询教师所属课程分页列表
     */
    @Query("""
            SELECT l FROM Lesson l
            WHERE l.teacherId = :teacherId
            AND (:statusList IS NULL OR l.status IN :statusList)
            AND (
                (:fromTime IS NULL AND :toTime IS NULL)
                OR l.scheduledAt BETWEEN :fromTime AND :toTime
            )
            ORDER BY l.scheduledAt DESC, l.createdAt DESC
            """)
    @Deprecated
    Page<Lesson> findTeacherLessonPage(
            @Param("teacherId") UUID teacherId,
            @Param("statusList") List<LessonStatus> statusList,
            @Param("fromTime") OffsetDateTime fromTime,
            @Param("toTime") OffsetDateTime toTime,
            Pageable pageable
    );

    /**
     * §5.5 更新课程状态
     */
    @Modifying
    @Query("UPDATE Lesson l SET l.status = :targetStatus, l.updatedAt = CURRENT_TIMESTAMP WHERE l.id = :lessonId")
    int updateLessonStatus(
            @Param("lessonId") UUID lessonId,
            @Param("targetStatus") LessonStatus targetStatus
    );

    // 教师作用域(分析 Agent 归属校验 / 发现层工具)

    /** 教师名下全部课程(发现层 list_my_lessons) */
    List<Lesson> findByTeacherIdOrderByScheduledAtDesc(UUID teacherId);

    /** 课程归属校验:该课程属于该教师 */
    boolean existsByIdAndTeacherId(UUID id, UUID teacherId);

    /** 班级归属校验:该 classCode 下有本教师课程 */
    boolean existsByClassCodeAndTeacherId(String classCode, UUID teacherId);
}
