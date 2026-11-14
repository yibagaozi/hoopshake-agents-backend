package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {

    Optional<KnowledgeDocument> findByDocId(String docId);

    Page<KnowledgeDocument> findByRemovedFalse(Pageable pageable);
}

