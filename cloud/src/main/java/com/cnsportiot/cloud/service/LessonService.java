package com.cnsportiot.cloud.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.dto.request.LessonRequests;
import com.cnsportiot.cloud.dto.response.LessonDtos;

public interface LessonService {

    /** §5.1 创建课程 */
    LessonDtos.LessonResponse create(LessonRequests.CreateLessonRequest request, UUID loginTeacherId);

    /** §5.2 课程分页列表 */
    Page<LessonDtos.LessonResponse> findTeacherLessons(UUID loginTeacherId,
                                                       List<LessonStatus> statusList,
                                                       OffsetDateTime fromTime,
                                                       OffsetDateTime toTime,
                                                       int page,
                                                       int size);

    /** §5.3 课程详情 */
    LessonDtos.LessonResponse getDetail(UUID lessonId, UUID loginTeacherId);

    /** §5.4 更新课程 */
    LessonDtos.LessonResponse update(UUID lessonId, LessonRequests.UpdateLessonRequest request, UUID loginTeacherId);

    /** §5.5 状态推进 */
    LessonDtos.LessonResponse updateStatus(UUID lessonId, LessonRequests.UpdateLessonStatusRequest request, UUID loginTeacherId);

    /** §5.9 课堂实况 */
    LessonDtos.LessonLiveResponse getLive(UUID lessonId, UUID loginTeacherId);
}
