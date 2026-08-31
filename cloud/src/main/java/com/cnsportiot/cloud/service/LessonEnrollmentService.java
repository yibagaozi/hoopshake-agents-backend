package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.request.EnrollmentRequests.*;
import com.cnsportiot.cloud.dto.response.EnrollmentDtos.*;

import java.util.List;
import java.util.UUID;

/** 参课名单  */
public interface LessonEnrollmentService {

    /** 5.6 参课名单 */
    List<EnrollmentItem> list(UUID lessonId, UUID loginTeacherId);

    /** 5.7 批量导入 */
    List<EnrollmentItem> importStudents(UUID lessonId, ImportEnrollmentRequest request, UUID loginTeacherId);

    /** 5.11 导入预检 */
    ImportPreviewResponse preview(UUID lessonId, ImportEnrollmentRequest request, UUID loginTeacherId);

    /** 5.8 取消报名(幂等,未报名亦成功) */
    void cancel(UUID lessonId, UUID studentId, UUID loginTeacherId);
}
