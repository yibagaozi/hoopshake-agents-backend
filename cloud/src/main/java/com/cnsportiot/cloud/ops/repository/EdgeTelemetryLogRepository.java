package com.cnsportiot.cloud.ops.repository;

import com.cnsportiot.cloud.ops.entity.EdgeTelemetryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface EdgeTelemetryLogRepository
        extends JpaRepository<EdgeTelemetryLog, java.util.UUID>, JpaSpecificationExecutor<EdgeTelemetryLog> {
    Optional<EdgeTelemetryLog> findByEventId(String eventId);
}
