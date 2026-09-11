package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.domain.enums.HelpRequestStatus;
import com.cnsportiot.cloud.dto.request.HelpRequestRequests.CreateHelpRequest;
import com.cnsportiot.cloud.dto.request.HelpRequestRequests.HandleHelpRequest;
import com.cnsportiot.cloud.dto.response.HelpRequestDtos.HelpRequestResponse;
import com.cnsportiot.cloud.dto.response.HelpRequestDtos.TeacherHelpRequestItem;
import com.cnsportiot.contracts.common.PageResponse;

import java.util.UUID;

/** 协助工单:学生发起 + 自查,教师列表拉取处理 */
public interface HelpRequestService {

    /** 学生发起协助工单 */
    HelpRequestResponse create(UUID studentId, CreateHelpRequest request);

    /** 学生查看自己的工单 */
    PageResponse<HelpRequestResponse> listForStudent(UUID studentId, int page, int size);

    /** 教师拉取名下学生的工单(status 可空=全部) */
    PageResponse<TeacherHelpRequestItem> listForTeacher(
            UUID teacherAccountId, HelpRequestStatus status, int page, int size);

    /** 教师处理工单(置状态 + 可选答复) */
    TeacherHelpRequestItem handle(UUID teacherAccountId, UUID requestId, HandleHelpRequest request);
}
