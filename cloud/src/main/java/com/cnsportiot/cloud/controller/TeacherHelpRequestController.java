package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.HelpRequestStatus;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.HelpRequestRequests.HandleHelpRequest;
import com.cnsportiot.cloud.dto.response.HelpRequestDtos.TeacherHelpRequestItem;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.HelpRequestService;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 教师端协助工单:拉取名下学生的协助请求,处理答复 */
@RestController
@RequestMapping("/api/teacher/help-requests")
@RequireRole(Role.TEACHER)
public class TeacherHelpRequestController {

    private final HelpRequestService helpRequestService;

    public TeacherHelpRequestController(HelpRequestService helpRequestService) {
        this.helpRequestService = helpRequestService;
    }

    /** 工单列表(status 可空=全部;仅本教师名下学生) */
    @GetMapping
    public ApiResponse<PageResponse<TeacherHelpRequestItem>> list(
            @RequestParam(required = false) HelpRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(helpRequestService.listForTeacher(me.accountId(), status, page, size));
    }

    /** 处理工单:置状态 + 可选答复 */
    @PostMapping("/{requestId}/handle")
    public ApiResponse<TeacherHelpRequestItem> handle(
            @PathVariable UUID requestId,
            @Valid @RequestBody HandleHelpRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(helpRequestService.handle(me.accountId(), requestId, request));
    }
}

