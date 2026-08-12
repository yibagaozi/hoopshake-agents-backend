package com.cnsportiot.cloud.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cnsportiot.cloud.domain.entity.ActionClip;

/** 动作片段仓储 */
public interface ActionClipRepository extends JpaRepository<ActionClip, UUID> {

    @Query("SELECT COUNT(DISTINCT c.studentId) FROM ActionClip c WHERE c.sessionId = :sessionId")
    long countDistinctStudentIdBySessionId(@Param("sessionId") UUID sessionId);

    interface ClipCountByAction {
        String getActionType();
        long getClipCount();
    }

    @Query("SELECT c.actionType AS actionType, COUNT(c) AS clipCount FROM ActionClip c WHERE c.sessionId = :sessionId GROUP BY c.actionType")
    List<ClipCountByAction> countClipByActionType(@Param("sessionId") UUID sessionId);

    interface StudentActionStat {
        String getActionType();
        long getClipCount();
        java.math.BigDecimal getMadeRate();
        java.time.OffsetDateTime getLastAt();
    }

    @Query("SELECT c.actionType AS actionType, COUNT(c) AS clipCount, "
            + "AVG(CASE WHEN c.shotMade = true THEN 1 WHEN c.shotMade = false THEN 0 END) AS madeRate, "
            + "MAX(s.recordedAt) AS lastAt "
            + "FROM ActionClip c, TrainingSession s "
            + "WHERE c.sessionId = s.id AND c.studentId = :studentId "
            + "GROUP BY c.actionType")
    List<StudentActionStat> findActionStatsByStudentId(@Param("studentId") UUID studentId);

    @Query("SELECT COUNT(c) FROM ActionClip c WHERE c.studentId = :studentId")
    long countByStudentId(@Param("studentId") UUID studentId);

    @Query("SELECT COUNT(DISTINCT c.sessionId) FROM ActionClip c WHERE c.studentId = :studentId")
    long countDistinctSessionByStudentId(@Param("studentId") UUID studentId);

    long countBySessionId(UUID sessionId);

    @Query("SELECT ac.actionType AS actionType, COUNT(ac) AS cnt FROM ActionClip ac WHERE ac.sessionId = :sessionId GROUP BY ac.actionType")
    List<ActionTypeCount> countBySessionIdGroupByActionType(@Param("sessionId") UUID sessionId);

    interface ActionTypeCount {
        String getActionType();
        long getCnt();
    }

    @Query("SELECT DISTINCT ac.studentId FROM ActionClip ac WHERE ac.sessionId = :sessionId")
    List<UUID> findDistinctStudentIdsBySessionId(@Param("sessionId") UUID sessionId);

    @Query("SELECT ac.studentId AS studentId, COUNT(ac) AS cnt FROM ActionClip ac WHERE ac.sessionId = :sessionId GROUP BY ac.studentId")
    List<StudentClipCount> countBySessionIdGroupByStudentId(@Param("sessionId") UUID sessionId);

    interface StudentClipCount {
        UUID getStudentId();
        long getCnt();
    }

    @Query("SELECT AVG(CASE WHEN ac.shotMade = true THEN 1.0 ELSE 0.0 END) FROM ActionClip ac WHERE ac.sessionId = :sessionId AND ac.shotMade IS NOT NULL")
    java.math.BigDecimal avgMadeRateBySessionId(@Param("sessionId") UUID sessionId);

    boolean existsBySessionIdAndStudentIdAndClipIndex(UUID sessionId, UUID studentId, int clipIndex);
}
