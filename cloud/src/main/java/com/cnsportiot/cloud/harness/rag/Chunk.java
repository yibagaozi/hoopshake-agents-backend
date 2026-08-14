package com.cnsportiot.cloud.harness.rag;

import java.util.List;

/**
 * 一个切分后的知识片段(见 §8.3)。
 *
 * @param text        用于嵌入/展示的正文(不含面包屑头,头在 {@link #embedText()} 拼)
 * @param headingPath 标题路径(面包屑),如 ["投篮技术","原地投篮","原地立定投篮"]
 * @param sectionTitle 叶子标题(headingPath 末元素),可能为空
 * @param chunkIndex  同一源文档内的序号,从 0 起
 */
public record Chunk(
        String text,
        List<String> headingPath,
        String sectionTitle,
        int chunkIndex) {

    /** 分隔符:面包屑各级之间。 */
    public static final String PATH_SEP = " › ";

    /**
     * 实际送去 embedding 的文本 = 面包屑头 + 正文(见 §8.3 上下文头增召回)。
     * 无标题路径时直接返回正文。
     */
    public String embedText() {
        if (headingPath == null || headingPath.isEmpty()) {
            return text;
        }
        return String.join(PATH_SEP, headingPath) + "\n" + text;
    }
}
