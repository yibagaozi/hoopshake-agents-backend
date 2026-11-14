package com.cnsportiot.cloud.harness.rag;

import java.util.List;

/**
 * 一条召回结果
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

    /** 注入 prompt 用的文本 */
    public String toContext() {
        return text;
    }
}