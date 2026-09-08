package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.InstantFeedback;
import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.domain.entity.TrainingSession;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.dto.response.SummaryDtos.ClassSummaryResponse;
import com.cnsportiot.cloud.repository.ActionClipRepository;
import com.cnsportiot.cloud.repository.InstantFeedbackRepository;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.cloud.repository.TrainingSessionRepository;
import com.cnsportiot.contracts.enums.FeedbackSeverity;
import com.cnsportiot.contracts.enums.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
/**
 * §8.1 课末汇总：验证 session 归属校验、出勤统计、动作汇总、安全提醒和学生汇总行构造。
 */
class TeacherSummaryServiceImplTest {

    @Mock
    private TrainingSessionRepository trainingSessionRepository;

    @Mock
    private LessonRepository lessonRepository;

    @Mock
    private LessonEnrollmentRepository lessonEnrollmentRepository;

    @Mock
    private ActionClipRepository actionClipRepository;

    @Mock
    private InstantFeedbackRepository instantFeedbackRepository;

    @Mock
    private StudentRepository studentRepository;

    @InjectMocks
    private TeacherSummaryServiceImpl service;

    // §8.1 汇总：按 lesson.teacher_id 校验权限，并聚合出勤、总动作、平均命中率和安全提醒。
    @Test
    void getSessionSummary_buildsAttendanceAndStudentRows() {
        UUID teacherId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID studentId1 = UUID.randomUUID();
        UUID studentId2 = UUID.randomUUID();

        Lesson lesson = Lesson.builder()
                .teacherId(teacherId)
                .title("Basketball")
                .classCode("T1")
                .durationMinutes(60)
                .status(LessonStatus.FINISHED)
                .actionTypes(List.of("shot"))
                .enabledCheckpoints(List.of("stance"))
                .build();
        ReflectionTestUtils.setField(lesson, "id", lessonId);

        TrainingSession session = TrainingSession.builder()
                .lessonId(lessonId)
                .status(SessionStatus.REPORT_READY)
                .recordedAt(OffsetDateTime.parse("2026-08-21T09:00:00Z"))
                .build();
        ReflectionTestUtils.setField(session, "id", sessionId);

        StudentRepository.StudentRef ref1 = org.mockito.Mockito.mock(StudentRepository.StudentRef.class);
        org.mockito.Mockito.when(ref1.getStudentId()).thenReturn(studentId1);
        org.mockito.Mockito.when(ref1.getDisplayName()).thenReturn("Alice");
        StudentRepository.StudentRef ref2 = org.mockito.Mockito.mock(StudentRepository.StudentRef.class);
        org.mockito.Mockito.when(ref2.getStudentId()).thenReturn(studentId2);
        org.mockito.Mockito.when(ref2.getDisplayName()).thenReturn("Bob");

        ActionClipRepository.StudentClipCount clipCount = org.mockito.Mockito.mock(ActionClipRepository.StudentClipCount.class);
        org.mockito.Mockito.when(clipCount.getStudentId()).thenReturn(studentId1);
        org.mockito.Mockito.when(clipCount.getCnt()).thenReturn(3L);

        InstantFeedbackRepository.StudentCheckpointCount checkpointCount = org.mockito.Mockito.mock(InstantFeedbackRepository.StudentCheckpointCount.class);
        org.mockito.Mockito.when(checkpointCount.getStudentId()).thenReturn(studentId1);
        org.mockito.Mockito.when(checkpointCount.getCheckpointId()).thenReturn("stance");
        org.mockito.Mockito.when(checkpointCount.getCnt()).thenReturn(2L);

        InstantFeedbackRepository.CheckpointCount checkpointDistribution = org.mockito.Mockito.mock(InstantFeedbackRepository.CheckpointCount.class);
        org.mockito.Mockito.when(checkpointDistribution.getCheckpointId()).thenReturn("stance");
        org.mockito.Mockito.when(checkpointDistribution.getCnt()).thenReturn(2L);

        InstantFeedback majorFeedback = InstantFeedback.builder()
                .studentId(studentId1)
                .actionType("shot")
                .checkpointId("stance")
                .severity(FeedbackSeverity.MAJOR)
                .cueText("Keep balance")
                .occurredAt(OffsetDateTime.parse("2026-08-21T09:05:00Z"))
                .build();

        given(trainingSessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(lessonRepository.findById(lessonId)).willReturn(Optional.of(lesson));
        given(lessonEnrollmentRepository.findStudentIdsByLessonId(lessonId)).willReturn(List.of(studentId1, studentId2));
        given(actionClipRepository.findDistinctStudentIdsBySessionId(sessionId)).willReturn(List.of(studentId1));
        given(actionClipRepository.countBySessionId(sessionId)).willReturn(3L);
        given(actionClipRepository.avgMadeRateBySessionId(sessionId)).willReturn(new BigDecimal("0.67"));
        given(studentRepository.findRefsByIdIn(anySet())).willReturn(List.of(ref1, ref2));
        given(instantFeedbackRepository.findBySessionIdAndSeverity(sessionId, FeedbackSeverity.MAJOR)).willReturn(List.of(majorFeedback));
        given(instantFeedbackRepository.countBySessionIdGroupByCheckpoint(sessionId)).willReturn(List.of(checkpointDistribution));
        given(actionClipRepository.countBySessionIdGroupByStudentId(sessionId)).willReturn(List.of(clipCount));
        given(instantFeedbackRepository.countBySessionIdGroupByStudentAndCheckpoint(sessionId)).willReturn(List.of(checkpointCount));

        ClassSummaryResponse result = service.getSessionSummary(sessionId, teacherId);

        assertThat(result.lessonTitle()).isEqualTo("Basketball");
        assertThat(result.attendance().present()).isEqualTo(1);
        assertThat(result.attendance().enrolled()).isEqualTo(2);
        assertThat(result.totalClips()).isEqualTo(3L);
        assertThat(result.avgMadeRate()).isEqualByComparingTo(new BigDecimal("0.67"));
        assertThat(result.safetyAlerts()).hasSize(1);
        assertThat(result.students()).hasSize(2);
        assertThat(result.students()).anySatisfy(row -> {
            assertThat(row.studentId()).isEqualTo(studentId1);
            assertThat(row.clipCount()).isEqualTo(3L);
            assertThat(row.recognized()).isTrue();
        });
    }
}
