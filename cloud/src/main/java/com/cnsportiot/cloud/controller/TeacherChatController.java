package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.TeacherChatRequests.CreateTeacherChatSessionRequest;
import com.cnsportiot.cloud.dto.request.TeacherChatRequests.RenameTeacherChatSessionRequest;
import com.cnsportiot.cloud.dto.request.TeacherChatRequests.TeacherChatAskRequest;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatMessageResponse;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatSessionResponse;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.TeacherChatService;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** 教师分析对话(TEACHING_ANALYST):取数 分析 总结,走教师作用域工具 */
@RestController
@RequestMapping("/api/teacher/chat")
@RequireRole(Role.TEACHER)
public class TeacherChatController {

    private final TeacherChatService teacherChatService;

    public TeacherChatController(TeacherChatService teacherChatService) {
        this.teacherChatService = teacherChatService;
    }

    @PostMapping("/sessions")
    public ApiResponse<ChatSessionResponse> create(
            @Valid @RequestBody CreateTeacherChatSessionRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(teacherChatService.createSession(request, me.accountId()));
    }

    @GetMapping("/sessions")
    public ApiResponse<PageResponse<ChatSessionResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(teacherChatService.listSessions(me.accountId(), page, size));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<PageResponse<ChatMessageResponse>> messages(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(teacherChatService.listMessages(sessionId, me.accountId(), page, size));
    }

    /** 流式提问(SSE:meta/delta/tool/done/error) */
    @PostMapping(value = "/sessions/{sessionId}/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(
            @PathVariable UUID sessionId,
            @Valid @RequestBody TeacherChatAskRequest request,
            @CurrentUser AuthUser me) {
        return teacherChatService.ask(sessionId, request, me.accountId());
    }

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<ChatSessionResponse> rename(
            @PathVariable UUID sessionId,
            @Valid @RequestBody RenameTeacherChatSessionRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(teacherChatService.rename(sessionId, me.accountId(), request.title()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> delete(@PathVariable UUID sessionId, @CurrentUser AuthUser me) {
        teacherChatService.deleteSession(sessionId, me.accountId());
        return ApiResponse.ok(null);
    }
}

