package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.HelpRequest;
import com.cnsportiot.cloud.domain.enums.HelpRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 协助工单仓储。教师归属 = 学生所在课程的 teacher_id(经报名表 join)。 */
public interface HelpRequestRepository extends JpaRepository<HelpRequest, UUID> {

    /** 学生查看自己的工单 */
    Page<HelpRequest> findByStudentIdOrderByCreatedAtDesc(UUID studentId, Pageable pageable);

    /** 判断某学生是否属于该教师(教师任一课程报名了该生)——用于教师处理时的越权校验 */
    @Query("""
            SELECT COUNT(e) > 0 FROM LessonEnrollment e
                 JOIN Lesson l ON l.id = e.lessonId
            WHERE l.teacherId = :teacherId AND e.studentId = :studentId
            """)
    boolean isStudentOfTeacher(@Param("teacherId") UUID teacherId, @Param("studentId") UUID studentId);

    /** 教师端工单视图(带学生学号/姓名) */
    interface HelpRequestView {
        UUID getId();
        UUID getStudentId();
        String getStudentNo();
        String getDisplayName();
        UUID getLessonId();
        UUID getSessionId();
        String getQuestion();
        HelpRequestStatus getStatus();
        String getTeacherReply();
        OffsetDateTime getCreatedAt();
        OffsetDateTime getHandledAt();
    }

    /** 教师端全部工单 */
    @Query("""
        SELECT hr.id           AS id,
               hr.studentId    AS studentId,
               s.studentNo     AS studentNo,
               a.displayName   AS displayName,
               hr.lessonId     AS lessonId,
               hr.sessionId    AS sessionId,
               hr.question     AS question,
               hr.status       AS status,
               hr.teacherReply AS teacherReply,
               hr.createdAt    AS createdAt,
               hr.handledAt    AS handledAt
        FROM HelpRequest hr
             JOIN Student s ON s.id = hr.studentId
             JOIN Account a ON a.id = s.accountId
        WHERE hr.studentId IN (
                SELECT e.studentId FROM LessonEnrollment e
                     JOIN Lesson l ON l.id = e.lessonId
                WHERE l.teacherId = :teacherId)
          AND (:status IS NULL OR hr.status = :status)
        ORDER BY hr.createdAt DESC
        """)
    Page<HelpRequestView> findForTeacher(@Param("teacherId") UUID teacherId,
                                     @Param("status") HelpRequestStatus status,
                                     Pageable pageable);

    /** 单条工单视图(带学生学号/姓名),处理后回显用;调用前需自行校验教师归属 */
    @Query("""
            SELECT hr.id           AS id,
                   hr.studentId    AS studentId,
                   s.studentNo     AS studentNo,
                   a.displayName   AS displayName,
                   hr.lessonId     AS lessonId,
                   hr.sessionId    AS sessionId,
                   hr.question     AS question,
                   hr.status       AS status,
                   hr.teacherReply AS teacherReply,
                   hr.createdAt    AS createdAt,
                   hr.handledAt    AS handledAt
            FROM HelpRequest hr
                 JOIN Student s ON s.id = hr.studentId
                 JOIN Account a ON a.id = s.accountId
            WHERE hr.id = :id
            """)
    Optional<HelpRequestView> findViewById(@Param("id") UUID id);
}
