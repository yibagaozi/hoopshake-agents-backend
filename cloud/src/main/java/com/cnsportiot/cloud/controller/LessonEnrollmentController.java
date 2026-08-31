package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.EnrollmentRequests;
import com.cnsportiot.cloud.dto.response.EnrollmentDtos;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.service.LessonEnrollmentService;
import com.cnsportiot.contracts.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 参课名单(5.6 5.7 5.8 5.11)
 * Excel 由前端解析为 {@code students[]} 提交,服务端不接收文件。
 */
@RestController
@RequestMapping("/api/teacher/lessons/{lessonId}/enrollments")
@RequireRole(Role.TEACHER)
public class LessonEnrollmentController {

    private final LessonEnrollmentService enrollmentService;

    public LessonEnrollmentController(LessonEnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /** 5.6 参课名单 */
    @GetMapping
    public ApiResponse<List<EnrollmentDtos.EnrollmentItem>> list(
            @PathVariable UUID lessonId,
            @CurrentUser AuthUser me) {

        return ApiResponse.ok(enrollmentService.list(lessonId, me.accountId()));
    }

    /** 5.7 批量导入 */
    @PostMapping
    public ApiResponse<List<EnrollmentDtos.EnrollmentItem>> importStudents(
            @PathVariable UUID lessonId,
            @Valid @RequestBody EnrollmentRequests.ImportEnrollmentRequest request,
            @CurrentUser AuthUser me) {

        return ApiResponse.ok(enrollmentService.importStudents(lessonId, request, me.accountId()));
    }

    /** 5.11 导入预检 */
    @PostMapping("/preview")
    public ApiResponse<EnrollmentDtos.ImportPreviewResponse> preview(
            @PathVariable UUID lessonId,
            @RequestBody EnrollmentRequests.ImportEnrollmentRequest request,
            @CurrentUser AuthUser me) {

        return ApiResponse.ok(enrollmentService.preview(lessonId, request, me.accountId()));
    }

    /** 5.8 取消报名(幂等,未报名亦返回成功) */
    @DeleteMapping("/{studentId}")
    public ApiResponse<Void> cancel(
            @PathVariable UUID lessonId,
            @PathVariable UUID studentId,
            @CurrentUser AuthUser me) {

        enrollmentService.cancel(lessonId, studentId, me.accountId());
        return ApiResponse.ok();
    }
}

