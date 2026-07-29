package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.service.EnrollmentService;
import com.cnsportiot.edge.dto.EnrollmentDtos.EnrollProgressResponse;
import com.cnsportiot.edge.dto.EnrollmentDtos.EnrollTaskResponse;
import com.cnsportiot.edge.dto.EnrollmentDtos.StartEnrollRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 姿态特征采集 —— 预留 */
@RestController
@RequestMapping("/local/enroll")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /** 启动采集 */
    @PostMapping("/start")
    public ApiResponse<EnrollTaskResponse> start(@Valid @RequestBody StartEnrollRequest request) {
        return ApiResponse.ok(enrollmentService.start(request));
    }

    /** 采集进度 */
    @GetMapping("/{taskId}/progress")
    public ApiResponse<EnrollProgressResponse> progress(@PathVariable UUID taskId) {
        return ApiResponse.ok(enrollmentService.progress(taskId));
    }
}

