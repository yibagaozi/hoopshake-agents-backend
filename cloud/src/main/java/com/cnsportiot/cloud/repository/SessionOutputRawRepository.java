package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.SessionOutputRaw;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SessionOutputRawRepository extends JpaRepository<SessionOutputRaw, UUID> {

    boolean existsByEventId(String eventId);
}
