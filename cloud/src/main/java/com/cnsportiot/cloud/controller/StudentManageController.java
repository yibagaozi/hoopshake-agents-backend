package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.StudentManageRequests.RegisterStudentRequest;
import com.cnsportiot.cloud.dto.request.StudentManageRequests.UpdateStudentRequest;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.RegisterStudentResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentBriefResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentDetailResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentStatsResponse;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.StudentManageService;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 学生管理 */
@RestController
@RequestMapping("/api/teacher/students")
@RequireRole(Role.TEACHER)
public class StudentManageController {

    private final StudentManageService studentManageService;

    public StudentManageController(StudentManageService studentManageService) {
        this.studentManageService = studentManageService;
    }

    /** 6.2 学生检索:分页返回学生列表 */
    @GetMapping
    public ApiResponse<PageResponse<StudentBriefResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(studentManageService.listStudents(keyword, page, size));
    }

    /** 6.3 学生详情 */
    @GetMapping("/{studentId}")
    public ApiResponse<StudentDetailResponse> detail(
            @PathVariable UUID studentId) {
        return ApiResponse.ok(studentManageService.getStudentDetail(studentId));
    }

    /** 6.4 更新档案:仅允许修改教师可编辑字段 */
    @PutMapping("/{studentId}")
    public ApiResponse<StudentDetailResponse> update(
            @PathVariable UUID studentId,
            @Valid @RequestBody UpdateStudentRequest request) {
        return ApiResponse.ok(studentManageService.updateStudent(studentId, request));
    }

    /** 6.5 学生统计 */
    @GetMapping("/{studentId}/stats")
    public ApiResponse<StudentStatsResponse> stats(
            @PathVariable UUID studentId) {
        return ApiResponse.ok(studentManageService.getStudentStats(studentId));
    }

    /** 6.1 学生建档 */
    @PostMapping
    public ApiResponse<RegisterStudentResponse> register(
            @Valid @RequestBody RegisterStudentRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(studentManageService.registerStudent(request, me.accountId().toString()));
    }
}
