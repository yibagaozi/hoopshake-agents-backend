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

    /** 某学生某会话的即时反馈流水 */
    List<InstantFeedback> findByStudentIdAndSessionIdOrderByOccurredAtAsc(UUID studentId, UUID sessionId);

    // 学生训练数据端点
    long countByStudentIdAndSessionId(UUID studentId, UUID sessionId);
    org.springframework.data.domain.Page<InstantFeedback>
        findByStudentIdAndSessionId(UUID studentId, UUID sessionId, org.springframework.data.domain.Pageable pageable);
    org.springframework.data.domain.Page<InstantFeedback>
        findByStudentIdAndSessionIdAndSeverity(UUID studentId, UUID sessionId, FeedbackSeverity severity,
                                               org.springframework.data.domain.Pageable pageable);

    /** 一组学生的共性问题(按 severity 过滤,按受影响人数降序) */
    interface CommonIssueRow {
        String getCheckpointId();
        long getAffected();
        long getTotal();
    }

    @Query("""
            SELECT f.checkpointId AS checkpointId,
                   COUNT(DISTINCT f.studentId) AS affected,
                   COUNT(f) AS total
            FROM InstantFeedback f
            WHERE f.studentId IN :ids AND f.severity = :severity
            GROUP BY f.checkpointId
            ORDER BY COUNT(DISTINCT f.studentId) DESC
            """)
    List<CommonIssueRow> findCommonIssues(@Param("ids") Collection<UUID> ids,
                                          @Param("severity") FeedbackSeverity severity);

    /** 某学生某会话按检查点聚合(major 命中数 / 总数),供单次检查点通过率 */
    interface CheckpointAgg {
        String getCheckpointId();
        long getMajor();
        long getTotal();
    }

    @Query("""
            SELECT f.checkpointId AS checkpointId,
                   SUM(CASE WHEN f.severity = com.cnsportiot.contracts.enums.FeedbackSeverity.MAJOR THEN 1 ELSE 0 END) AS major,
                   COUNT(f) AS total
            FROM InstantFeedback f
            WHERE f.studentId = :sid AND f.sessionId = :sessionId
            GROUP BY f.checkpointId
            """)
    List<CheckpointAgg> checkpointAgg(@Param("sid") UUID sid, @Param("sessionId") UUID sessionId);
}
