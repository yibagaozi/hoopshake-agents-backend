package com.cnsportiot.cloud.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cnsportiot.cloud.domain.entity.TrainingSession;
import com.cnsportiot.contracts.enums.SessionStatus;

/** 训练会话仓储 */
public interface TrainingSessionRepository extends JpaRepository<TrainingSession, UUID> {

    Optional<TrainingSession> findFirstByLessonIdAndStatusNotOrderByRecordedAtDesc(UUID lessonId, SessionStatus status);

    @Query("SELECT MAX(s.recordedAt) FROM TrainingSession s JOIN ActionClip c ON c.sessionId = s.id WHERE c.studentId = :studentId")
    Optional<OffsetDateTime> findLastRecordedAtByStudentId(@Param("studentId") UUID studentId);

    Optional<TrainingSession> findByIdAndLessonId(UUID id, UUID lessonId);

    List<TrainingSession> findByLessonId(UUID lessonId);

    @Query("SELECT ts FROM TrainingSession ts WHERE ts.lessonId = :lessonId AND ts.status = :status ORDER BY ts.recordedAt DESC")
    List<TrainingSession> findByLessonIdAndStatus(@Param("lessonId") UUID lessonId, @Param("status") SessionStatus status);

    @Query("SELECT ts FROM TrainingSession ts WHERE ts.lessonId = :lessonId ORDER BY ts.recordedAt DESC LIMIT 1")
    Optional<TrainingSession> findLatestByLessonId(@Param("lessonId") UUID lessonId);

    // 学生训练数据端点:某学生参与过的会话(有其片段),按生成时间倒序,可选日期过滤
    @Query("""
            SELECT DISTINCT s FROM TrainingSession s JOIN ActionClip c ON c.sessionId = s.id
            WHERE c.studentId = :sid
              AND (cast(:from as java.time.OffsetDateTime) IS NULL OR s.generatedAt >= :from)
              AND (cast(:to as java.time.OffsetDateTime) IS NULL OR s.generatedAt <= :to)
            ORDER BY s.generatedAt DESC
            """)
    Page<TrainingSession> findSessionsByStudent(
            @Param("sid") UUID sid,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            Pageable pageable);
}
