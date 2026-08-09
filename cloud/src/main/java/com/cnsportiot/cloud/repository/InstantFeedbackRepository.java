package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.InstantFeedback;
import com.cnsportiot.contracts.enums.FeedbackSeverity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InstantFeedbackRepository extends JpaRepository<InstantFeedback, UUID> {

    boolean existsByEventId(String eventId);

    @Query("SELECT fb.checkpointId AS checkpointId, COUNT(fb) AS cnt FROM InstantFeedback fb WHERE fb.sessionId = :sessionId GROUP BY fb.checkpointId")
    List<CheckpointCount> countBySessionIdGroupByCheckpoint(@Param("sessionId") UUID sessionId);

    interface CheckpointCount {
        String getCheckpointId();
        long getCnt();
    }

    @Query("SELECT fb FROM InstantFeedback fb WHERE fb.sessionId = :sessionId AND fb.severity = :severity ORDER BY fb.occurredAt DESC")
    List<InstantFeedback> findBySessionIdAndSeverity(@Param("sessionId") UUID sessionId, @Param("severity") FeedbackSeverity severity);

    long countBySessionIdAndSeverity(UUID sessionId, FeedbackSeverity severity);

    @Query("SELECT fb FROM InstantFeedback fb WHERE fb.sessionId = :sessionId ORDER BY fb.occurredAt DESC LIMIT :limit")
    List<InstantFeedback> findRecentBySessionId(@Param("sessionId") UUID sessionId, @Param("limit") int limit);

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
