package com.cnsportiot.cloud.harness.rag;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**  agent 关闭时的兜底 RagStore,导入拒绝 */
@Component
@ConditionalOnProperty(prefix = "hoopshake.agent", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopRagStore implements RagStore {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public List<String> load(String docId, List<Chunk> chunks, Map<String, Object> baseMetadata) {
        throw new BusinessException(ErrorCode.LLM_UNAVAILABLE, "知识库未启用(hoopshake.agent.enabled=false)");
    }

    @Override
    public void deleteChunks(List<String> chunkIds) {
        // no-op
    }

    @Override
    public List<Snippet> search(String query, int topK, double similarityThreshold) {
        return List.of();
    }
}

