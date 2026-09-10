package com.cnsportiot.cloud.ops.service;

import com.cnsportiot.cloud.dto.response.ChatDtos.ChatMessageResponse;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatSessionResponse;
import com.cnsportiot.cloud.ops.dto.OpsChatRequests.CreateOpsChatSessionRequest;
import com.cnsportiot.cloud.ops.dto.OpsChatRequests.OpsChatAskRequest;
import com.cnsportiot.contracts.common.PageResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** 运维诊断对话(Ops Assistant,只读诊断):会话 CRUD + 流式提问(取数→诊断→建议) */
public interface OpsChatService {

    ChatSessionResponse createSession(CreateOpsChatSessionRequest request, UUID ownerAccountId);

    PageResponse<ChatSessionResponse> listSessions(UUID ownerAccountId, int page, int size);

    PageResponse<ChatMessageResponse> listMessages(UUID sessionId, UUID ownerAccountId, int page, int size);

    ChatSessionResponse rename(UUID sessionId, UUID ownerAccountId, String title);

    void deleteSession(UUID sessionId, UUID ownerAccountId);

    /** 流式提问(SSE:meta/delta/tool/done/error) */
    SseEmitter ask(UUID sessionId, OpsChatAskRequest request, UUID ownerAccountId);
}
