package com.cnsportiot.cloud.ops.repository;

import com.cnsportiot.cloud.ops.entity.EdgeProcessRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface EdgeProcessRunRepository
        extends JpaRepository<EdgeProcessRun, UUID>, JpaSpecificationExecutor<EdgeProcessRun> {
    Optional<EdgeProcessRun> findByRunId(UUID runId);
}
