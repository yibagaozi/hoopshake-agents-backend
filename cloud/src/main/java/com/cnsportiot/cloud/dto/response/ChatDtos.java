package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.cloud.domain.enums.MessageRole;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 学生问答响应 DTO */
public final class ChatDtos {
    private ChatDtos() {}

    /** 3.1 新建会话 / 3.2 会话列表项 */
    public record ChatSessionResponse(
            UUID sessionId,
            String title,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    /** 3.3 会话消息 */
    public record ChatMessageResponse(
            UUID messageId,
            MessageRole role,
            String content,
            Integer tokenUsage,   // 仅 ASSISTANT
            OffsetDateTime createdAt) {}

    // ===== 3.4 SSE 事件负载 =====

    /** event: meta */
    public record ChatMetaEvent(UUID messageId, UUID sessionId) {}

    /** event: delta */
    public record ChatDeltaEvent(String text) {}

    /** event: tool(工具调用轨迹,label 为可直接展示的中文短句) */
    public record ChatToolEvent(String name, String status, String label) {}

    /** event: rag(本轮召回并注入的知识片段,调试可视;生产前端可忽略) */
    public record ChatRagEvent(List<RagHit> hits) {}

    public record RagHit(String docId, String section, Double score) {}

    /** event: done(suggestions 为可选建议追问) */
    public record ChatDoneEvent(
            UUID messageId,
            String finishReason,  // stop / interrupted / length
            Integer tokenUsage,
            List<String> suggestions) {}
}

