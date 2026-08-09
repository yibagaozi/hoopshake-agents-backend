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
}
