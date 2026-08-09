package com.cnsportiot.cloud.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cnsportiot.cloud.domain.entity.TrainingSession;
import com.cnsportiot.contracts.enums.SessionStatus;

/** 训练会话仓储 */
public interface TrainingSessionRepository extends JpaRepository<TrainingSession, UUID> {

    Optional<TrainingSession> findFirstByLessonIdAndStatusNotOrderByRecordedAtDesc(UUID lessonId, SessionStatus status);
}
