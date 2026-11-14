package com.cnsportiot.cloud.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cnsportiot.cloud.domain.entity.CheckpointKnowledge;

/** 检查点知识库仓储 */
public interface CheckpointKnowledgeRepository extends JpaRepository<CheckpointKnowledge, UUID> {

    @Query(value = "SELECT checkpoint_id FROM checkpoint_knowledge WHERE metadata->>'safety' = 'true'", nativeQuery = true)
    List<String> findSafetyCheckpointIds();
}
