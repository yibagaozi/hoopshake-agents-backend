package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.ChatRequests.ChatAskRequest;
import com.cnsportiot.cloud.dto.request.ChatRequests.CreateChatSessionRequest;
import com.cnsportiot.cloud.dto.request.ChatRequests.RenameChatSessionRequest;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatMessageResponse;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatSessionResponse;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.ChatService;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** 学生对话(API §3)。数据主体固定为 token 中的 studentId。 */
@RestController
@RequestMapping("/api/student/chat")
@RequireRole(Role.STUDENT)
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** §3.1 新建会话 */
    @PostMapping("/sessions")
    public ApiResponse<ChatSessionResponse> create(
            @Valid @RequestBody CreateChatSessionRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(chatService.createSession(request, me.studentId()));
    }

    /** §3.2 会话列表 */
    @GetMapping("/sessions")
    public ApiResponse<PageResponse<ChatSessionResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(chatService.listSessions(me.studentId(), page, size));
    }

    /** §3.3 会话消息 */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<PageResponse<ChatMessageResponse>> messages(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(chatService.listMessages(sessionId, me.studentId(), page, size));
    }

    /** §3.4 提问(SSE):meta / delta / done / error */
    @PostMapping(value = "/sessions/{sessionId}/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(
            @PathVariable UUID sessionId,
            @Valid @RequestBody ChatAskRequest request,
            @CurrentUser AuthUser me) {
        return chatService.ask(sessionId, request, me.studentId());
    }

    /** §3.5 会话改名 */
    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<ChatSessionResponse> rename(
            @PathVariable UUID sessionId,
            @Valid @RequestBody RenameChatSessionRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(chatService.rename(sessionId, me.studentId(), request.title()));
    }

    /** §3.6 中断生成。幂等。 */
    @PostMapping("/sessions/{sessionId}/interrupt")
    public ApiResponse<Void> interrupt(@PathVariable UUID sessionId, @CurrentUser AuthUser me) {
        chatService.interrupt(sessionId, me.studentId());
        return ApiResponse.ok();
    }

    /** §3.7 删除会话(软删)。幂等。 */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> delete(@PathVariable UUID sessionId, @CurrentUser AuthUser me) {
        chatService.deleteSession(sessionId, me.studentId());
        return ApiResponse.ok();
    }
}
