package com.cnsportiot.cloud.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 知识库管理请求 DTO */
public final class KnowledgeRequests {
    private KnowledgeRequests() {}

    /**
     * 8.1 导入/登记一篇文档(本里程碑接收 Markdown 文本;.docx 走离线 Pandoc 转 md,见 8.2)
     * docId 缺省则由服务端按内容生成
     */
    public record ImportDocumentRequest(
            @Size(max = 128) String docId,
            @Size(max = 32) String source,     // textbook / team
            @Size(max = 64) String domain,     // 如 shooting
            @Size(max = 64) String actionType,
            @NotBlank(message = "文档内容不能为空") String content) {}

    /** 8.6 调试检索 */
    public record DebugSearchRequest(
            @NotBlank(message = "query 不能为空") String query,
            Integer topK) {}
}
