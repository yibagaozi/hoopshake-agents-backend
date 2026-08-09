package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.IngestEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IngestEventRepository extends JpaRepository<IngestEvent, UUID> {

    boolean existsByEventId(String eventId);
}
