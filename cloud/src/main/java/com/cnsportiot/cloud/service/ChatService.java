package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.request.ChatRequests.ChatAskRequest;
import com.cnsportiot.cloud.dto.request.ChatRequests.CreateChatSessionRequest;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatMessageResponse;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatSessionResponse;
import com.cnsportiot.contracts.common.PageResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** 学生对话 */
public interface ChatService {

    ChatSessionResponse createSession(CreateChatSessionRequest request, UUID studentId);

    PageResponse<ChatSessionResponse> listSessions(UUID studentId, int page, int size);

    PageResponse<ChatMessageResponse> listMessages(UUID sessionId, UUID studentId, int page, int size);

    ChatSessionResponse rename(UUID sessionId, UUID studentId, String title);

    void deleteSession(UUID sessionId, UUID studentId);

    /** 3.4 提问,SSE 流式(meta/delta/done/error) */
    SseEmitter ask(UUID sessionId, ChatAskRequest request, UUID studentId);

    /** 3.6 中断当前生成。幂等 */
    void interrupt(UUID sessionId, UUID studentId);
}
