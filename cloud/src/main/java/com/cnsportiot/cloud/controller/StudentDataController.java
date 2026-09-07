package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.StudentDataRequests.UpdateProfileRequest;
import com.cnsportiot.cloud.dto.response.StudentDataDtos.*;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.StudentDataService;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 学生训练数据;报告(report)未开放 */
@RestController
@RequestMapping("/api/student")
@RequireRole(Role.STUDENT)
public class StudentDataController {

    private final StudentDataService service;

    public StudentDataController(StudentDataService service) {
        this.service = service;
    }

    @GetMapping("/data/overview")
    public ApiResponse<OverviewResponse> overview(@CurrentUser AuthUser me) {
        return ApiResponse.ok(service.overview(me.studentId()));
    }

    @GetMapping("/data/sessions")
    public ApiResponse<PageResponse<SessionBrief>> sessions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(service.listSessions(me.studentId(), from, to, page, size));
    }

    @GetMapping("/data/sessions/{sessionId}")
    public ApiResponse<SessionDetail> sessionDetail(@PathVariable UUID sessionId, @CurrentUser AuthUser me) {
        return ApiResponse.ok(service.sessionDetail(me.studentId(), sessionId));
    }

    @GetMapping("/data/sessions/{sessionId}/clips")
    public ApiResponse<PageResponse<ClipBrief>> clips(
            @PathVariable UUID sessionId,
            @RequestParam(required = false) String actionType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(service.listClips(me.studentId(), sessionId, actionType, page, size));
    }

    @GetMapping("/data/clips/{clipId}")
    public ApiResponse<ClipDetail> clipDetail(@PathVariable UUID clipId, @CurrentUser AuthUser me) {
        return ApiResponse.ok(service.clipDetail(me.studentId(), clipId));
    }

    @GetMapping("/data/sessions/{sessionId}/feedback")
    public ApiResponse<PageResponse<FeedbackItem>> feedback(
            @PathVariable UUID sessionId,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(service.listFeedback(me.studentId(), sessionId, severity, page, size));
    }

    @GetMapping("/data/trend")
    public ApiResponse<TrendResponse> trend(
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String metric,
            @RequestParam(defaultValue = "20") int limit,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(service.trend(me.studentId(), actionType, metric, limit));
    }

    @PutMapping("/profile")
    public ApiResponse<ProfileResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request, @CurrentUser AuthUser me) {
        return ApiResponse.ok(service.updateProfile(me.studentId(), request));
    }
}

