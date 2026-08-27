package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.domain.entity.KnowledgeDocument;
import com.cnsportiot.cloud.domain.enums.ActionType;
import com.cnsportiot.cloud.domain.enums.KnowledgeDocStatus;
import com.cnsportiot.cloud.dto.request.KnowledgeRequests.DebugSearchRequest;
import com.cnsportiot.cloud.dto.request.KnowledgeRequests.ImportDocumentRequest;
import com.cnsportiot.cloud.dto.response.KnowledgeDtos.DocumentResponse;
import com.cnsportiot.cloud.dto.response.KnowledgeDtos.ImportAcceptedResponse;
import com.cnsportiot.cloud.dto.response.KnowledgeDtos.SearchHitResponse;
import com.cnsportiot.cloud.common.PageResponses;
import com.cnsportiot.cloud.harness.audit.AuditAction;
import com.cnsportiot.cloud.harness.audit.AuditService;
import com.cnsportiot.cloud.harness.rag.KnowledgeIngestWorker;
import com.cnsportiot.cloud.harness.rag.RagStore;
import com.cnsportiot.cloud.harness.rag.Snippet;
import com.cnsportiot.cloud.repository.KnowledgeDocumentRepository;
import com.cnsportiot.cloud.service.KnowledgeAdminService;
import com.cnsportiot.contracts.common.PageResponse;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 知识库管理实现。导入/重导不加 {@code @Transactional}  */
@Service
@RequiredArgsConstructor
public class KnowledgeAdminServiceImpl implements KnowledgeAdminService {

    private final KnowledgeDocumentRepository docRepo;
    private final KnowledgeIngestWorker worker;
    private final RagStore ragStore;
    private final AuditService auditService;
    private final AgentProperties props;

    @Override
    public ImportAcceptedResponse importDocument(ImportDocumentRequest request, UUID operatorAccountId) {
        requireEnabled();
        String content = request.content();
        String hash = sha256(content);
        String docId = (request.docId() == null || request.docId().isBlank())
                ? "doc-" + hash.substring(0, 16)
                : request.docId().strip();
        String actionType = resolveActionType(request.actionType());

        KnowledgeDocument doc = docRepo.findByDocId(docId).orElse(null);

        // 幂等:同 docId 且内容未变且已完成 → 跳过重灌
        if (doc != null && !doc.isRemoved()
                && hash.equals(doc.getContentHash())
                && doc.getStatus() == KnowledgeDocStatus.DONE) {
            return new ImportAcceptedResponse(doc.getId(), doc.getDocId(), doc.getStatus());
        }

        if (doc == null) {
            doc = KnowledgeDocument.builder()
                    .docId(docId)
                    .source(request.source())
                    .domain(request.domain())
                    .actionType(actionType)
                    .version(1)
                    .status(KnowledgeDocStatus.PROCESSING)
                    .build();
        } else {
            doc.setSource(request.source());
            doc.setDomain(request.domain());
            doc.setActionType(actionType);
            doc.setVersion(doc.getVersion() + 1);
            doc.setStatus(KnowledgeDocStatus.PROCESSING);
            doc.setRemoved(false);
            doc.setErrorMessage(null);
        }
        doc.setContent(content);
        doc.setContentHash(hash);
        doc.setSplitParams(splitParamsSnapshot());
        doc = docRepo.save(doc);   // 独立提交

        auditService.record(operatorAccountId, AuditAction.KNOWLEDGE_IMPORT, null,
                Map.of("docId", docId, "version", doc.getVersion()));

        worker.process(doc.getId());   // 异步灌库
        return new ImportAcceptedResponse(doc.getId(), doc.getDocId(), doc.getStatus());
    }

    @Override
    public PageResponse<DocumentResponse> list(int page, int size) {
        Pageable pageable = PageResponses.toPageable(page, size);
        return PageResponses.from(docRepo.findByRemovedFalse(pageable), this::toDto);
    }

    @Override
    public DocumentResponse get(String docId) {
        return toDto(docRepo.findByDocId(docId)
                .orElseThrow(() -> BusinessException.notFound("知识文档不存在")));
    }

    @Override
    public void delete(String docId, UUID operatorAccountId) {
        KnowledgeDocument doc = docRepo.findByDocId(docId).orElse(null);
        if (doc == null || doc.isRemoved()) {
            return;   // 幂等
        }
        ragStore.deleteChunks(doc.getChunkIds());
        doc.setRemoved(true);
        doc.setChunkCount(0);
        docRepo.save(doc);
        auditService.record(operatorAccountId, AuditAction.KNOWLEDGE_DELETE, null,
                Map.of("docId", docId));
    }

    @Override
    public ImportAcceptedResponse reindex(String docId, UUID operatorAccountId) {
        requireEnabled();
        KnowledgeDocument doc = docRepo.findByDocId(docId)
                .orElseThrow(() -> BusinessException.notFound("知识文档不存在"));
        doc.setStatus(KnowledgeDocStatus.PROCESSING);
        doc.setSplitParams(splitParamsSnapshot());
        docRepo.save(doc);
        auditService.record(operatorAccountId, AuditAction.KNOWLEDGE_REINDEX, null,
                Map.of("docId", docId));
        worker.process(doc.getId());
        return new ImportAcceptedResponse(doc.getId(), doc.getDocId(), doc.getStatus());
    }

    @Override
    public List<SearchHitResponse> debugSearch(DebugSearchRequest request) {
        int topK = request.topK() == null ? props.getRag().getTopK() : request.topK();
        List<Snippet> hits = ragStore.search(request.query(), topK, props.getRag().getSimilarityThreshold());
        return hits.stream()
                .map(s -> new SearchHitResponse(s.docId(), s.sectionTitle(), s.headingPath(), s.score(), s.text()))
                .toList();
    }

    private void requireEnabled() {
        if (!ragStore.isEnabled()) {
            throw new BusinessException(ErrorCode.LLM_UNAVAILABLE, "知识库未启用(hoopshake.agent.enabled=false)");
        }
    }

    private String resolveActionType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ActionType.UNKNOWN.getValue();
        }
        return ActionType.fromValue(raw)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID,
                        "未知动作类型: " + raw + "(允许: " + ActionType.allowedValues() + ")"))
                .getValue();
    }

    private DocumentResponse toDto(KnowledgeDocument d) {
        return new DocumentResponse(
                d.getId(), d.getDocId(), d.getSource(), d.getDomain(), d.getActionType(), d.getCheckpointId(),
                d.getVersion(), d.getStatus(), d.getChunkCount(), d.getErrorMessage(),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    private Map<String, Object> splitParamsSnapshot() {
        AgentProperties.Chunk c = props.getChunk();
        return Map.of(
                "strategy", "HEADING_TREE_FIRST",
                "maxTokens", c.getMaxTokens(),
                "overlapTokens", c.getOverlapTokens(),
                "minChunkChars", c.getMinChunkChars());
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

