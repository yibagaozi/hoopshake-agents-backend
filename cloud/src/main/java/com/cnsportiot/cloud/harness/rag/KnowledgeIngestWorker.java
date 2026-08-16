package com.cnsportiot.cloud.harness.rag;

import com.cnsportiot.cloud.domain.entity.KnowledgeDocument;
import com.cnsportiot.cloud.domain.enums.KnowledgeDocStatus;
import com.cnsportiot.cloud.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异步灌库 worker。独立 bean 以规避 {@code @Async} 自调用失效
 *
 * 流程:读 catalog 源正文 → 切分(cpu)→ 删旧 chunk(重导/reindex 幂等)→ 灌新 chunk(embedding IO,
 * 在事务外)→ 回写 catalog(状态/chunk 数/ids)。失败置 FAILED,不抛(异步无人接)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestWorker {

    private final KnowledgeDocumentRepository docRepo;
    private final MarkdownChunker chunker;
    private final RagStore ragStore;

    @Async("knowledgeImportExecutor")
    public void process(UUID rowId) {
        KnowledgeDocument doc = docRepo.findById(rowId).orElse(null);
        if (doc == null) {
            log.warn("知识灌库:catalog 行不存在 rowId={}", rowId);
            return;
        }
        try {
            // 幂等:重导/reindex 先删旧 chunk
            if (doc.getChunkIds() != null && !doc.getChunkIds().isEmpty()) {
                ragStore.deleteChunks(doc.getChunkIds());
            }

            List<Chunk> chunks = chunker.chunk(doc.getContent());

            Map<String, Object> base = new HashMap<>();
            base.put("source", doc.getSource());
            base.put("domain", doc.getDomain());
            if (doc.getCheckpointId() != null) {
                base.put("checkpoint_id", doc.getCheckpointId());
            }

            List<String> ids = ragStore.load(doc.getDocId(), chunks, base);

            doc.setChunkIds(ids);
            doc.setChunkCount(ids.size());
            doc.setStatus(KnowledgeDocStatus.DONE);
            doc.setErrorMessage(null);
            docRepo.save(doc);
            log.info("知识灌库完成 docId={} chunks={}", doc.getDocId(), ids.size());
        } catch (RuntimeException e) {
            log.error("知识灌库失败 docId={}", doc.getDocId(), e);
            doc.setStatus(KnowledgeDocStatus.FAILED);
            String msg = e.getMessage();
            doc.setErrorMessage(msg == null ? e.getClass().getSimpleName()
                    : msg.substring(0, Math.min(msg.length(), 500)));
            docRepo.save(doc);
        }
    }
}

