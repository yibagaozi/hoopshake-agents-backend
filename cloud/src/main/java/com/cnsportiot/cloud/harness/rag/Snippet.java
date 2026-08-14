package com.cnsportiot.cloud.harness.rag;

import java.util.List;

/**
 * 一条召回结果(见 §8.5)。
 *
 * @param text        chunk 正文
 * @param sectionTitle 叶子标题
 * @param headingPath 面包屑
 * @param docId       源文档标识
 * @param score       相似度(越大越相关)
 */
public record Snippet(
        String text,
        String sectionTitle,
        List<String> headingPath,
        String docId,
        Double score) {

    /**
     * 注入 prompt 用的文本。灌库时已把面包屑头写进 chunk 正文(见 SpringAiRagStore.load 用
     * {@link Chunk#embedText()}),故此处直接返回 {@link #text()},不再重复拼头。
     */
    public String toContext() {
        return text;
    }
}
