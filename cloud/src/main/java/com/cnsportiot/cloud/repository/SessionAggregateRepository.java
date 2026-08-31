package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.SessionAggregate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SessionAggregateRepository extends JpaRepository<SessionAggregate, UUID> {

    List<SessionAggregate> findBySessionIdAndStudentId(UUID sessionId, UUID studentId);

    boolean existsBySessionIdAndStudentIdAndActionType(UUID sessionId, UUID studentId, String actionType);
}
