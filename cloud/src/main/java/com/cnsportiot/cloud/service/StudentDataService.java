package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.request.StudentDataRequests.UpdateProfileRequest;
import com.cnsportiot.cloud.dto.response.StudentDataDtos.*;
import com.cnsportiot.contracts.common.PageResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 学生训练数据(API §4):概览 / 会话 / 片段 / 反馈 / 趋势 / 档案 */
public interface StudentDataService {

    OverviewResponse overview(UUID studentId);

    PageResponse<SessionBrief> listSessions(UUID studentId, OffsetDateTime from, OffsetDateTime to, int page, int size);

    SessionDetail sessionDetail(UUID studentId, UUID sessionId);

    PageResponse<ClipBrief> listClips(UUID studentId, UUID sessionId, String actionType, int page, int size);

    ClipDetail clipDetail(UUID studentId, UUID clipId);

    PageResponse<FeedbackItem> listFeedback(UUID studentId, UUID sessionId, String severity, int page, int size);

    TrendResponse trend(UUID studentId, String actionType, String metric, int limit);

    ProfileResponse updateProfile(UUID studentId, UpdateProfileRequest request);
}
