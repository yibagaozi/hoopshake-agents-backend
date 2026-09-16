package com.cnsportiot.cloud.ops.repository;

import com.cnsportiot.cloud.ops.entity.EdgeTelemetryMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface EdgeTelemetryMetricRepository
        extends JpaRepository<EdgeTelemetryMetric, java.util.UUID>, JpaSpecificationExecutor<EdgeTelemetryMetric> {
    Optional<EdgeTelemetryMetric> findByEventId(String eventId);
}
