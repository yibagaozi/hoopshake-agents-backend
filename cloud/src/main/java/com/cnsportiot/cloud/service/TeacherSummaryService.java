package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.response.SummaryDtos.ClassSummaryResponse;

import java.util.UUID;

public interface TeacherSummaryService {

    ClassSummaryResponse getSessionSummary(UUID sessionId, UUID teacherAccountId);
}