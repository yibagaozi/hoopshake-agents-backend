package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.dto.request.LessonRequests.CreateLessonRequest;
import com.cnsportiot.cloud.dto.request.LessonRequests.UpdateLessonStatusRequest;
import com.cnsportiot.cloud.dto.response.LessonDtos.LessonResponse;
import com.cnsportiot.cloud.repository.ActionClipRepository;
import com.cnsportiot.cloud.repository.CheckpointKnowledgeRepository;
import com.cnsportiot.cloud.repository.InstantFeedbackRepository;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import com.cnsportiot.cloud.repository.TrainingSessionRepository;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
/**
 * §5 课程管理：验证课程创建、列表查询、状态推进等教师端核心业务规则。
 * 重点覆盖归属校验、动作字典校验、状态单向流转等逻辑。
 */
class LessonServiceImplTest {

    @Mock
    private LessonRepository lessonRepository;

    @Mock
    private LessonEnrollmentRepository lessonEnrollmentRepository;

    @Mock
    private TrainingSessionRepository trainingSessionRepository;

    @Mock
    private ActionClipRepository actionClipRepository;

    @Mock
    private InstantFeedbackRepository instantFeedbackRepository;

    @Mock
    private CheckpointKnowledgeRepository checkpointKnowledgeRepository;

    @InjectMocks
    private LessonServiceImpl service;

    private Lesson lesson(UUID lessonId, UUID teacherId, LessonStatus status) {
        Lesson lesson = Lesson.builder()
                .teacherId(teacherId)
                .title("Basketball")
                .actionTypes(List.of("shot", "layup"))
                .enabledCheckpoints(List.of("stance"))
                .status(status)
                .build();
        ReflectionTestUtils.setField(lesson, "id", lessonId);
        return lesson;
    }

    // §5.1 创建课程：教师只能创建属于自己的课程，且必须写入指定的 actionTypes / status。
    @Test
    void create_validRequest_savesLessonAndReturnsResponse() {
        UUID teacherId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        CreateLessonRequest request = new CreateLessonRequest(
                "Basketball", "A1", List.of("shot", "layup"), List.of("stance"), "zone-1", "middle",
                OffsetDateTime.parse("2026-08-21T09:00:00Z"), 60
        );

        given(lessonRepository.save(any(Lesson.class))).willAnswer(invocation -> {
            Lesson saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", lessonId);
            return saved;
        });

        LessonResponse response = service.create(request, teacherId);

        assertThat(response.lessonId()).isEqualTo(lessonId);
        assertThat(response.teacherId()).isEqualTo(teacherId);
        assertThat(response.actionTypes()).containsExactly("shot", "layup");
        assertThat(response.status()).isEqualTo(LessonStatus.PLANNED);
        then(lessonRepository).should().save(org.mockito.ArgumentMatchers.argThat(lesson ->
                lesson.getTeacherId().equals(teacherId)
                        && lesson.getTitle().equals("Basketball")
                        && lesson.getStatus() == LessonStatus.PLANNED));
    }

    // §5.1 创建课程：非法动作类型必须拒绝，返回 PARAM_INVALID。
    @Test
    void create_invalidActionType_throwsBusinessException() {
        UUID teacherId = UUID.randomUUID();
        CreateLessonRequest request = new CreateLessonRequest(
                "Basketball", "A1", List.of("illegal"), null, null, null, null, 60
        );

        assertThatThrownBy(() -> service.create(request, teacherId))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    // §5.2 课程列表：按教师归属查询，并汇总每节课的报名人数。
    @Test
    void findTeacherLessons_countsEnrollmentPerLesson() {
        UUID teacherId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        Lesson lesson = lesson(lessonId, teacherId, LessonStatus.PLANNED);
        Page<Lesson> page = new PageImpl<>(List.of(lesson), PageRequest.of(0, 10), 1);

        LessonEnrollmentRepository.LessonEnrollmentCount count = org.mockito.Mockito.mock(LessonEnrollmentRepository.LessonEnrollmentCount.class);
        org.mockito.Mockito.when(count.getLessonId()).thenReturn(lessonId);
        org.mockito.Mockito.when(count.getCount()).thenReturn(3L);

        given(lessonRepository.findAll(any(Specification.class), any(Pageable.class))).willReturn(page);
        given(lessonEnrollmentRepository.countGroupByLessonId(anyList())).willReturn(List.of(count));

        Page<LessonResponse> result = service.findTeacherLessons(teacherId, List.of(LessonStatus.PLANNED), null, null, 0, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).enrolledCount()).isEqualTo(3);
    }

    // §5.5 状态推进：PLANNED -> ONGOING 允许推进，且需调用 repository 更新状态。
    @Test
    void updateStatus_advancesToOngoing() {
        UUID teacherId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        Lesson lesson = lesson(lessonId, teacherId, LessonStatus.PLANNED);
        Lesson refreshed = lesson(lessonId, teacherId, LessonStatus.ONGOING);

        given(lessonRepository.findById(lessonId)).willReturn(Optional.of(lesson), Optional.of(refreshed));

        LessonResponse response = service.updateStatus(lessonId, new UpdateLessonStatusRequest(LessonStatus.ONGOING), teacherId);

        assertThat(response.status()).isEqualTo(LessonStatus.ONGOING);
        then(lessonRepository).should().updateLessonStatus(lessonId, LessonStatus.ONGOING);
    }
}
