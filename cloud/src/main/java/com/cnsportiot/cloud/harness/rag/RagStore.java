package com.cnsportiot.cloud.harness.rag;

import java.util.List;
import java.util.Map;

/**
 * 向量库读写抽象(见 §8.4/§8.5)。业务/服务层只认本接口,不接触 Spring AI VectorStore。
 * 唯一真实现是 {@code adapter/spring/SpringAiRagStore};关闭时用 {@code NoopRagStore} 兜底。
 */
public interface RagStore {

    boolean isEnabled();

    /**
     * 灌库:把一篇文档的 chunk 写入向量表,返回其在向量表中的 chunk id 列表(供精确删除)。
     * embedding 由底层 VectorStore 内部完成。
     *
     * @param docId        源文档标识,写入每个 chunk 的 metadata.doc_id
     * @param chunks       切分结果
     * @param baseMetadata 文档级公共 metadata(source/domain/checkpoint_id 等)
     */
    List<String> load(String docId, List<Chunk> chunks, Map<String, Object> baseMetadata);

    /** 按 chunk id 精确删除(下架/重导)。 */
    void deleteChunks(List<String> chunkIds);

    /** 语义召回。当前纯语义(无 checkpoint 过滤,见 §8.5)。 */
    List<Snippet> search(String query, int topK, double similarityThreshold);
}
