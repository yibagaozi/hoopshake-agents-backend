package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.request.StudentManageRequests.RegisterStudentRequest;
import com.cnsportiot.cloud.dto.request.StudentManageRequests.UpdateStudentRequest;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.RegisterStudentResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentBriefResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentDetailResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentStatsResponse;
import com.cnsportiot.contracts.common.PageResponse;

import java.util.UUID;

public interface StudentManageService {

    /**
     * 6.1 学生建档:创建 account + student + audit_log
     */
    RegisterStudentResponse registerStudent(RegisterStudentRequest request, String operatorAccountId);

    /**
     * 6.2 学生检索:分页返回 student view
     */
    PageResponse<StudentBriefResponse> listStudents(String keyword, int page, int size);

    /**
     * 6.3 学生详情:student join account + active gallery
     */
    StudentDetailResponse getStudentDetail(UUID studentId);

    /**
     * 6.4 更新档案:仅 displayName / dominantHand / heightCm / legLengthCm / gradeBand
     */
    StudentDetailResponse updateStudent(UUID studentId, UpdateStudentRequest request);

    /**
     * 6.5 学生数据统计:跨全部 session 聚合
     */
    StudentStatsResponse getStudentStats(UUID studentId);
}
