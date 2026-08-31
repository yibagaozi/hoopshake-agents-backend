package com.cnsportiot.cloud.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cnsportiot.cloud.domain.entity.InstantFeedback;
import com.cnsportiot.contracts.enums.FeedbackSeverity;

/** 即时反馈仓储 */
public interface InstantFeedbackRepository extends JpaRepository<InstantFeedback, UUID> {

    boolean existsByEventId(String eventId);

    interface FeedbackView {
        UUID getFeedbackId();
        UUID getStudentId();
        String getDisplayName();
        String getActionType();
        String getCheckpointId();
        FeedbackSeverity getSeverity();
        String getCueText();
        OffsetDateTime getOccurredAt();
    }

    @Query("""
            SELECT i.id AS feedbackId,
                   i.studentId AS studentId,
                   a.displayName AS displayName,
                   i.actionType AS actionType,
                   i.checkpointId AS checkpointId,
                   i.severity AS severity,
                   i.cueText AS cueText,
                   i.occurredAt AS occurredAt
            FROM InstantFeedback i, Student s, Account a
            WHERE i.studentId = s.id
              AND s.accountId = a.id
              AND i.sessionId = :sessionId
            ORDER BY i.occurredAt DESC
            """)
    List<FeedbackView> findRecentBySessionId(@Param("sessionId") UUID sessionId, Pageable pageable);

    @Query("""
            SELECT i.id AS feedbackId,
                   i.studentId AS studentId,
                   a.displayName AS displayName,
                   i.actionType AS actionType,
                   i.checkpointId AS checkpointId,
                   i.severity AS severity,
                   i.cueText AS cueText,
                   i.occurredAt AS occurredAt
            FROM InstantFeedback i, Student s, Account a
            WHERE i.studentId = s.id
              AND s.accountId = a.id
              AND i.sessionId = :sessionId
              AND i.checkpointId IN :checkpointIds
            ORDER BY i.occurredAt DESC
            """)
    List<FeedbackView> findRecentSafetyBySessionIdAndCheckpointIds(
            @Param("sessionId") UUID sessionId,
            @Param("checkpointIds") Collection<String> checkpointIds,
            Pageable pageable);

    @Query("SELECT fb.checkpointId AS checkpointId, COUNT(fb) AS cnt FROM InstantFeedback fb WHERE fb.sessionId = :sessionId GROUP BY fb.checkpointId")
    List<CheckpointCount> countBySessionIdGroupByCheckpoint(@Param("sessionId") UUID sessionId);

    interface CheckpointCount {
        String getCheckpointId();
        long getCnt();
    }

    @Query("SELECT fb FROM InstantFeedback fb WHERE fb.sessionId = :sessionId AND fb.severity = :severity ORDER BY fb.occurredAt DESC")
    List<InstantFeedback> findBySessionIdAndSeverity(@Param("sessionId") UUID sessionId, @Param("severity") FeedbackSeverity severity);

    long countBySessionIdAndSeverity(UUID sessionId, FeedbackSeverity severity);

    @Query("""
            SELECT fb.studentId AS studentId, fb.checkpointId AS checkpointId, COUNT(fb) AS cnt
            FROM InstantFeedback fb
            WHERE fb.sessionId = :sessionId
            GROUP BY fb.studentId, fb.checkpointId
            ORDER BY cnt DESC
            """)
    List<StudentCheckpointCount> countBySessionIdGroupByStudentAndCheckpoint(@Param("sessionId") UUID sessionId);

    interface StudentCheckpointCount {
        UUID getStudentId();
        String getCheckpointId();
        long getCnt();
    }
}
