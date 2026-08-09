package com.cnsportiot.cloud.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cnsportiot.cloud.domain.entity.TrainingSession;
import com.cnsportiot.contracts.enums.SessionStatus;

/** 训练会话仓储 */
public interface TrainingSessionRepository extends JpaRepository<TrainingSession, UUID> {

    Optional<TrainingSession> findFirstByLessonIdAndStatusNotOrderByRecordedAtDesc(UUID lessonId, SessionStatus status);

    @Query("SELECT MAX(s.recordedAt) FROM TrainingSession s JOIN ActionClip c ON c.sessionId = s.id WHERE c.studentId = :studentId")
    Optional<java.time.OffsetDateTime> findLastRecordedAtByStudentId(@Param("studentId") UUID studentId);
}
