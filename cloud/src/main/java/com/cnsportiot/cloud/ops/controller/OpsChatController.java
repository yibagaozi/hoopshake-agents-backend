package com.cnsportiot.cloud.ops.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatMessageResponse;
import com.cnsportiot.cloud.dto.response.ChatDtos.ChatSessionResponse;
import com.cnsportiot.cloud.ops.service.OpsChatService;
import com.cnsportiot.cloud.ops.dto.OpsChatRequests.CreateOpsChatSessionRequest;
import com.cnsportiot.cloud.ops.dto.OpsChatRequests.OpsChatAskRequest;
import com.cnsportiot.cloud.ops.dto.OpsChatRequests.RenameOpsChatSessionRequest;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** 运维诊断对话(Ops Assistant):只读诊断,取数→归因→建议,走 OPS 作用域工具。ADMIN 专属。 */
@RestController
@RequestMapping("/api/ops/chat")
@RequireRole(Role.ADMIN)
public class OpsChatController {

    private final OpsChatService opsChatService;

    public OpsChatController(OpsChatService opsChatService) {
        this.opsChatService = opsChatService;
    }

    @PostMapping("/sessions")
    public ApiResponse<ChatSessionResponse> create(
            @Valid @RequestBody CreateOpsChatSessionRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(opsChatService.createSession(request, me.accountId()));
    }

    @GetMapping("/sessions")
    public ApiResponse<PageResponse<ChatSessionResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(opsChatService.listSessions(me.accountId(), page, size));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<PageResponse<ChatMessageResponse>> messages(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(opsChatService.listMessages(sessionId, me.accountId(), page, size));
    }

    /** 流式提问(SSE:meta/delta/tool/done/error)。 */
    @PostMapping(value = "/sessions/{sessionId}/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(
            @PathVariable UUID sessionId,
            @Valid @RequestBody OpsChatAskRequest request,
            @CurrentUser AuthUser me) {
        return opsChatService.ask(sessionId, request, me.accountId());
    }

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<ChatSessionResponse> rename(
            @PathVariable UUID sessionId,
            @Valid @RequestBody RenameOpsChatSessionRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(opsChatService.rename(sessionId, me.accountId(), request.title()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> delete(@PathVariable UUID sessionId, @CurrentUser AuthUser me) {
        opsChatService.deleteSession(sessionId, me.accountId());
        return ApiResponse.ok(null);
    }
}
