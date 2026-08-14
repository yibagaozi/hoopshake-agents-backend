package com.cnsportiot.cloud.harness.adapter.spring;

import com.cnsportiot.cloud.harness.rag.Chunk;
import com.cnsportiot.cloud.harness.rag.RagStore;
import com.cnsportiot.cloud.harness.rag.Snippet;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link RagStore} 的 Spring AI 实现 —— 唯一 import {@code VectorStore}/{@code Document} 的地方
 * (§1 ArchUnit)。用 Spring AI 自管的向量表(方案 A,§8.0);embedding 由 VectorStore 内部完成。
 * 仅当 {@code hoopshake.agent.enabled=true} 时装配。
 */
@Service
@ConditionalOnProperty(prefix = "hoopshake.agent", name = "enabled", havingValue = "true")
public class SpringAiRagStore implements RagStore {

    private final VectorStore vectorStore;

    public SpringAiRagStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<String> load(String docId, List<Chunk> chunks, Map<String, Object> baseMetadata) {
        List<Document> docs = new ArrayList<>(chunks.size());
        List<String> ids = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            // 显式 UUID,兼容 pgvector 的 uuid 主键列,且便于精确删除
            String id = UUID.randomUUID().toString();
            Map<String, Object> md = new HashMap<>();
            if (baseMetadata != null) {
                baseMetadata.forEach((k, v) -> { if (v != null) md.put(k, v); });
            }
            md.put("doc_id", docId);
            md.put("type", "knowledge");
            md.put("section_title", c.sectionTitle() == null ? "" : c.sectionTitle());
            md.put("heading_path", String.join(Chunk.PATH_SEP, c.headingPath()));
            md.put("chunk_index", c.chunkIndex());

            docs.add(Document.builder().id(id).text(c.embedText()).metadata(md).build());
            ids.add(id);
        }
        if (!docs.isEmpty()) {
            vectorStore.add(docs);
        }
        return ids;
    }

    @Override
    public void deleteChunks(List<String> chunkIds) {
        if (chunkIds != null && !chunkIds.isEmpty()) {
            vectorStore.delete(chunkIds);
        }
    }

    @Override
    public List<Snippet> search(String query, int topK, double similarityThreshold) {
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build());
        if (results == null) {
            return List.of();
        }
        List<Snippet> snippets = new ArrayList<>(results.size());
        for (Document d : results) {
            Map<String, Object> md = d.getMetadata();
            String pathStr = String.valueOf(md.getOrDefault("heading_path", ""));
            List<String> path = pathStr.isBlank() ? List.of() : Arrays.asList(pathStr.split(Chunk.PATH_SEP));
            snippets.add(new Snippet(
                    d.getText(),
                    String.valueOf(md.getOrDefault("section_title", "")),
                    path,
                    String.valueOf(md.getOrDefault("doc_id", "")),
                    d.getScore()));
        }
        return snippets;
    }
}
