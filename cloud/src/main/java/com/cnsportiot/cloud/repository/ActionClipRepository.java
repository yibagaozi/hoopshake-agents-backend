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
}
