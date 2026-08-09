package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.ActionClip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ActionClipRepository extends JpaRepository<ActionClip, UUID> {

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
