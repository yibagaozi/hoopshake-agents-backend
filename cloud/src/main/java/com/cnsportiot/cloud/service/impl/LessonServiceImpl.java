package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.dto.request.LessonRequests;
import com.cnsportiot.cloud.dto.response.LessonDtos;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import com.cnsportiot.cloud.service.LessonService;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LessonServiceImpl implements LessonService {

    private final LessonRepository lessonRepository;
    private final LessonEnrollmentRepository lessonEnrollmentRepository;

    private static final Set<String> VALID_ACTION_TYPES = new HashSet<>(Arrays.asList("shot", "layup", "dribble"));
    private static final Set<String> VALID_CHECKPOINTS = new HashSet<>(Arrays.asList("stance", "hand", "balance"));

    /** §5.1 创建课程 */
    @Override
    @Transactional
    public LessonDtos.LessonResponse create(LessonRequests.CreateLessonRequest request, UUID loginTeacherId) {
        // 校验动作/检查点字典
        if (!VALID_ACTION_TYPES.containsAll(request.actionTypes())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "包含非法训练动作");
        }
        if (request.enabledCheckpoints() != null && !VALID_CHECKPOINTS.containsAll(request.enabledCheckpoints())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "包含非法检查点");
        }

        // teacherId强制使用登录账号，忽略请求无关字段
        Lesson lesson = Lesson.builder()
                .teacherId(loginTeacherId)
                .title(request.title())
                .actionTypes(request.actionTypes())
                .enabledCheckpoints(Optional.ofNullable(request.enabledCheckpoints()).orElse(new ArrayList<>()))
                .zoneConfigRef(request.zoneConfigRef())
                .gradeBand(request.gradeBand())
                .scheduledAt(request.scheduledAt())
                .status(LessonStatus.PLANNED)
                .build();
        Lesson saved = lessonRepository.save(lesson);
        return convertToDto(saved, 0);
    }

    /** §5.2 课程分页列表 */
    @SuppressWarnings("null")
    @Override
    public Page<LessonDtos.LessonResponse> findTeacherLessons(UUID loginTeacherId,
                                                              List<LessonStatus> statusList,
                                                              OffsetDateTime fromTime,
                                                              OffsetDateTime toTime,
                                                              int page,
                                                              int size) {
        Pageable pageable = PageRequest.of(page, size);
      
        Page<Lesson> lessonPage = lessonRepository.findTeacherLessonPage(loginTeacherId, statusList, fromTime, toTime, pageable);

        List<UUID> lessonIdList = lessonPage.getContent().stream()
                .map(Lesson::getId)
                .collect(Collectors.toList());
        Map<UUID, Integer> enrollCountMap = new HashMap<>();
        if (!lessonIdList.isEmpty()) {
            List<Object[]> countRows = lessonEnrollmentRepository.countGroupByLessonId(lessonIdList);
            for (Object[] row : countRows) {
                UUID lid = (UUID) row[0];
                Long cnt = (Long) row[1];
                enrollCountMap.put(lid, cnt.intValue());
            }
        }

        return lessonPage.map(item -> {
            int count = enrollCountMap.getOrDefault(item.getId(), 0);
            return convertToDto(item, count);
        });
    }

    /** §5.3 课程详情 */
    @Override
    public LessonDtos.LessonResponse getDetail(UUID lessonId, UUID loginTeacherId) {
        Lesson lesson = checkLessonOwner(lessonId, loginTeacherId);
        long enrollTotal = lessonEnrollmentRepository.countByLessonId(lessonId);
        return convertToDto(lesson, (int) enrollTotal);
    }

    /** §5.4 更新课程配置 */
    @Override
    @Transactional
    public LessonDtos.LessonResponse update(UUID lessonId, LessonRequests.UpdateLessonRequest request, UUID loginTeacherId) {
        Lesson lesson = checkLessonOwner(lessonId, loginTeacherId);
        // 仅PLANNED允许修改，其他状态抛40910
        if (LessonStatus.PLANNED != lesson.getStatus()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "仅PLANNED状态课程可修改配置");
        }

        // 字典校验
        if (request.actionTypes() != null && !VALID_ACTION_TYPES.containsAll(request.actionTypes())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "包含非法训练动作");
        }
        if (request.enabledCheckpoints() != null && !VALID_CHECKPOINTS.containsAll(request.enabledCheckpoints())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "包含非法检查点");
        }

        // 增量更新，入参null字段不覆盖原有值
        if (request.title() != null) lesson.setTitle(request.title());
        if (request.actionTypes() != null) lesson.setActionTypes(request.actionTypes());
        if (request.enabledCheckpoints() != null) lesson.setEnabledCheckpoints(request.enabledCheckpoints());
        if (request.zoneConfigRef() != null) lesson.setZoneConfigRef(request.zoneConfigRef());
        if (request.gradeBand() != null) lesson.setGradeBand(request.gradeBand());
        if (request.scheduledAt() != null) lesson.setScheduledAt(request.scheduledAt());

        Lesson updated = lessonRepository.save(lesson);
        return convertToDto(updated, 0);
    }

    /** §5.5 课程状态单向流转 */
    @Override
    @Transactional
    public LessonDtos.LessonResponse updateStatus(UUID lessonId, LessonRequests.UpdateLessonStatusRequest request, UUID loginTeacherId) {
        Lesson lesson = checkLessonOwner(lessonId, loginTeacherId);
        LessonStatus target = request.status();

        // 状态序号：PLANNED=0, ONGOING=1, FINISHED=2
        int currOrder = switch (lesson.getStatus()) {
            case PLANNED -> 0;
            case ONGOING -> 1;
            case FINISHED -> 2;
        };
        int targetOrder = switch (target) {
            case PLANNED -> 0;
            case ONGOING -> 1;
            case FINISHED -> 2;
        };

        // 逆向流转抛出40910
        if (targetOrder < currOrder) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "不允许逆向变更课程状态");
        }
        // 同状态幂等，不执行更新直接返回
        if (target == lesson.getStatus()) {
            return convertToDto(lesson, 0);
        }

        // 调用Repository更新状态（自动更新updatedAt）
        lessonRepository.updateLessonStatus(lessonId, target);
        Lesson refreshed = lessonRepository.findById(lessonId)
                .orElseThrow(() -> BusinessException.notFound("课程不存在"));
        return convertToDto(refreshed, 0);
    }

    // ===================== 统一归属校验工具 =====================
    private Lesson checkLessonOwner(UUID lessonId, UUID loginTeacherId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> BusinessException.notFound("课程不存在"));
        if (!lesson.getTeacherId().equals(loginTeacherId)) {
            throw BusinessException.dataScopeDenied();
        }
        return lesson;
    }

    // ===================== 实体转响应DTO =====================
    private LessonDtos.LessonResponse convertToDto(Lesson lesson, int enrolledCount) {
        return new LessonDtos.LessonResponse(
                lesson.getId(),
                lesson.getTeacherId(),
                lesson.getTitle(),
                null, // 实体无classCode，协议占位null
                lesson.getActionTypes(),
                lesson.getEnabledCheckpoints(),
                lesson.getZoneConfigRef(),
                lesson.getGradeBand(),
                lesson.getScheduledAt(),
                null, // 实体无durationMinutes，协议占位null
                lesson.getStatus(),
                enrolledCount,
                lesson.getCreatedAt(),
                lesson.getUpdatedAt()
        );
    }
}
