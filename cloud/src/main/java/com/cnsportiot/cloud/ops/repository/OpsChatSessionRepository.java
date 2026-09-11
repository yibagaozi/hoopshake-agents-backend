package com.cnsportiot.cloud.ops.repository;

import com.cnsportiot.cloud.ops.entity.OpsChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 运维诊断会话仓储 */
public interface OpsChatSessionRepository extends JpaRepository<OpsChatSession, UUID> {

    Page<OpsChatSession> findByOwnerAccountIdAndDeletedFalse(UUID ownerAccountId, Pageable pageable);
}
