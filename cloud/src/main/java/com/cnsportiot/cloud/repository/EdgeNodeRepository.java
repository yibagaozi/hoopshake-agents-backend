package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.EdgeNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EdgeNodeRepository extends JpaRepository<EdgeNode, UUID> {

    Optional<EdgeNode> findByEdgeId(String edgeId);
}
