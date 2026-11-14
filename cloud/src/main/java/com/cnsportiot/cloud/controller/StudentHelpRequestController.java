package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.HelpRequestRequests.CreateHelpRequest;
import com.cnsportiot.cloud.dto.response.HelpRequestDtos.HelpRequestResponse;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.HelpRequestService;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 学生请求教师协助:发起,查看自己的工单 */
@RestController
@RequestMapping("/api/student/help-requests")
@RequireRole(Role.STUDENT)
public class StudentHelpRequestController {

    private final HelpRequestService helpRequestService;

    public StudentHelpRequestController(HelpRequestService helpRequestService) {
        this.helpRequestService = helpRequestService;
    }

    /** 发起协助(通常由 Agent 判定后展示的按钮触发) */
    @PostMapping
    public ApiResponse<HelpRequestResponse> create(
            @Valid @RequestBody CreateHelpRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(helpRequestService.create(me.studentId(), request));
    }

    /** 查看自己的工单(含教师答复/状态) */
    @GetMapping
    public ApiResponse<PageResponse<HelpRequestResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(helpRequestService.listForStudent(me.studentId(), page, size));
    }
}

