package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.response.SummaryDtos.ClassSummaryResponse;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.TeacherSummaryService;
import com.cnsportiot.contracts.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/teacher/summary")
@RequireRole(Role.TEACHER)
@RequiredArgsConstructor
public class TeacherSummaryController {

    private final TeacherSummaryService teacherSummaryService;

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<ClassSummaryResponse> sessionSummary(
            @PathVariable UUID sessionId,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(teacherSummaryService.getSessionSummary(sessionId, me.accountId()));
    }
}
