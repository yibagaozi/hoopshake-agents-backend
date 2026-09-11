package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.request.TeacherChatRequests.CreateTeacherChatSessionRequest;
import com.cnsportiot.cloud.dto.request.TeacherChatRequests.TeacherChatAskRequest;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatMessageResponse;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatSessionResponse;
import com.cnsportiot.contracts.common.PageResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** 教师分析对话(TEACHING_ANALYST):会话 CRUD + 流式提问(取数 分析 总结) */
public interface TeacherChatService {

    ChatSessionResponse createSession(CreateTeacherChatSessionRequest request, UUID teacherAccountId);

    PageResponse<ChatSessionResponse> listSessions(UUID teacherAccountId, int page, int size);

    PageResponse<ChatMessageResponse> listMessages(UUID sessionId, UUID teacherAccountId, int page, int size);

    ChatSessionResponse rename(UUID sessionId, UUID teacherAccountId, String title);

    void deleteSession(UUID sessionId, UUID teacherAccountId);

    /** 流式提问(SSE:meta/delta/tool/done/error) */
    SseEmitter ask(UUID sessionId, TeacherChatAskRequest request, UUID teacherAccountId);
}
