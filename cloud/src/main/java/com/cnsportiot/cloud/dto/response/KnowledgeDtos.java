package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.cloud.domain.enums.KnowledgeDocStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 知识库管理响应 DTO(API §8)。 */
public final class KnowledgeDtos {
    private KnowledgeDtos() {}

    /** §8.1 导入受理(异步):立即返回 docId + 初始状态。 */
    public record ImportAcceptedResponse(UUID id, String docId, KnowledgeDocStatus status) {}

    /** §8.2/§8.3 源文档目录项。 */
    public record DocumentResponse(
            UUID id,
            String docId,
            String source,
            String domain,
            String checkpointId,
            int version,
            KnowledgeDocStatus status,
            int chunkCount,
            String errorMessage,
            OffsetDateTime importedAt,
            OffsetDateTime updatedAt) {}

    /** §8.6 调试检索一条命中。 */
    public record SearchHitResponse(
            String docId,
            String sectionTitle,
            List<String> headingPath,
            Double score,
            String text) {}
}
