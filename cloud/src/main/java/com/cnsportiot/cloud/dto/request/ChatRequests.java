package com.cnsportiot.cloud.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 学生问答请求 DTO */
public final class ChatRequests {
    private ChatRequests() {}

    /** 3.1 新建会话 */
    public record CreateChatSessionRequest(
            @Size(max = 64) String title,
            UUID trainingSessionId) {}

    /** 3.4 提问(SSE) */
    public record ChatAskRequest(
            @NotBlank(message = "提问内容不能为空") @Size(max = 2000) String content,
            UUID trainingSessionId) {}
}
