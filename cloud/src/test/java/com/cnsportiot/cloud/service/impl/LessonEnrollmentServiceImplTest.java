package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.dto.request.EnrollmentRequests.ImportEnrollmentRequest;
import com.cnsportiot.cloud.dto.request.EnrollmentRequests.StudentEntry;
import com.cnsportiot.cloud.dto.response.EnrollmentDtos.ImportPreviewResponse;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.cloud.repository.StudentRepository.StudentRef;
import com.cnsportiot.cloud.service.StudentProvisioningService;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** LessonEnrollmentServiceImpl 单测:归属/结课校验、名单去重、缺失建档、幂等报名、预检分组与非法学号。 */
class LessonEnrollmentServiceImplTest {

    private static final UUID TEACHER = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("11111111-0000-0000-0000-000000000002");
    private static final UUID LESSON = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID SID_EXIST = UUID.fromString("33333333-0000-0000-0000-00000000000e");
    private static final UUID SID_NEW = UUID.fromString("33333333-0000-0000-0000-0000000000aa");

    private LessonRepository lessonRepo;
    private StudentRepository studentRepo;
    private LessonEnrollmentRepository enrollmentRepo;
    private StudentProvisioningService provisioning;
    private LessonEnrollmentServiceImpl svc;

    @BeforeEach
    void setup() {
        lessonRepo = mock(LessonRepository.class);
        studentRepo = mock(StudentRepository.class);
        enrollmentRepo = mock(LessonEnrollmentRepository.class);
        provisioning = mock(StudentProvisioningService.class);
        svc = new LessonEnrollmentServiceImpl(lessonRepo, studentRepo, enrollmentRepo, provisioning);
        lenient().when(enrollmentRepo.findEnrollmentView(any())).thenReturn(List.of());
    }

    private void lessonOwned(LessonStatus status) {
        Lesson l = Lesson.builder().teacherId(TEACHER).status(status).build();
        when(lessonRepo.findById(LESSON)).thenReturn(Optional.of(l));
    }

    private static StudentRef ref(UUID id, String no, String name) {
        return new StudentRef() {
            public UUID getStudentId() { return id; }
            public String getStudentNo() { return no; }
            public String getDisplayName() { return name; }
        };
    }

    // ---- 归属 / 结课 ----

    @Test void list_notFound() {
        when(lessonRepo.findById(LESSON)).thenReturn(Optional.empty());
        assertBusiness(() -> svc.list(LESSON, TEACHER), ErrorCode.NOT_FOUND);
    }

    @Test void list_notOwner_dataScopeDenied() {
        lessonOwned(LessonStatus.PLANNED);
        assertBusiness(() -> svc.list(LESSON, OTHER), ErrorCode.DATA_SCOPE_DENIED);
    }

    @Test void import_finishedLesson_stateConflict() {
        lessonOwned(LessonStatus.FINISHED);
        ImportEnrollmentRequest req = new ImportEnrollmentRequest(List.of(new StudentEntry("1234567890", "A")));
        assertBusiness(() -> svc.importStudents(LESSON, req, TEACHER), ErrorCode.STATE_CONFLICT);
    }

    @Test void cancel_finishedLesson_stateConflict() {
        lessonOwned(LessonStatus.FINISHED);
        assertBusiness(() -> svc.cancel(LESSON, SID_EXIST, TEACHER), ErrorCode.STATE_CONFLICT);
    }

    // import:去重 + 缺失建档 + 幂等报名

    @Test void import_dedupesProvisionsMissingAndEnrolls() {
        lessonOwned(LessonStatus.PLANNED);
        // 两条相同学号(去重后一条)+ 一条已存在
        ImportEnrollmentRequest req = new ImportEnrollmentRequest(List.of(
                new StudentEntry("1111111111", "New"),
                new StudentEntry("1111111111", "Dup"),      // 同号,被去重
                new StudentEntry("2222222222", "Exist")));
        when(studentRepo.findRefsByStudentNoIn(any()))
                .thenReturn(List.of(ref(SID_EXIST, "2222222222", "Exist")));   // 只有 2222 已存在
        when(provisioning.provisionByStudentNo(eq("1111111111"), any())).thenReturn(SID_NEW);
        when(enrollmentRepo.enrollIfAbsent(eq(LESSON), any())).thenReturn(1);

        svc.importStudents(LESSON, req, TEACHER);

        // 缺失的建档一次;已存在的不建档
        verify(provisioning, times(1)).provisionByStudentNo(eq("1111111111"), any());
        verify(provisioning, never()).provisionByStudentNo(eq("2222222222"), any());
        // 去重后 2 人各报名一次
        verify(enrollmentRepo).enrollIfAbsent(LESSON, SID_NEW);
        verify(enrollmentRepo).enrollIfAbsent(LESSON, SID_EXIST);
        verify(enrollmentRepo, times(2)).enrollIfAbsent(eq(LESSON), any());
    }

    // ---- preview:分组 + 非法学号 ----

    @Test void preview_classifiesInvalidCreateEnrollAlready() {
        lessonOwned(LessonStatus.PLANNED);
        ImportEnrollmentRequest req = new ImportEnrollmentRequest(List.of(
                new StudentEntry("123", "BadNo"),            // 非 10 位 → invalid
                new StudentEntry("1000000001", "ToCreate"),  // 不存在 → willCreate
                new StudentEntry("1000000002", "Enroll"),    // 存在未报名 → willEnroll
                new StudentEntry("1000000003", "Already")));  // 存在已报名 → alreadyEnrolled
        UUID enrollId = UUID.randomUUID();
        UUID alreadyId = UUID.randomUUID();
        when(studentRepo.findRefsByStudentNoIn(any())).thenReturn(List.of(
                ref(enrollId, "1000000002", "Enroll"),
                ref(alreadyId, "1000000003", "Already")));
        when(enrollmentRepo.findStudentIdsByLessonId(LESSON)).thenReturn(List.of(alreadyId));

        ImportPreviewResponse r = svc.preview(LESSON, req, TEACHER);
        assertThat(r.invalid()).extracting("studentNo").containsExactly("123");
        assertThat(r.willCreate()).extracting("studentNo").containsExactly("1000000001");
        assertThat(r.willEnroll()).extracting("studentId").containsExactly(enrollId);
        assertThat(r.alreadyEnrolled()).extracting("studentId").containsExactly(alreadyId);
        assertThat(r.total()).isEqualTo(4);
        verifyNoInteractions(provisioning);   // 预检不建档
        verify(enrollmentRepo, never()).enrollIfAbsent(any(), any());   // 预检不写
    }

    @Test void preview_existingStudent_usesDbDisplayNameNotRequestName() {
        lessonOwned(LessonStatus.PLANNED);
        ImportEnrollmentRequest req = new ImportEnrollmentRequest(List.of(
                new StudentEntry("1000000002", "TeacherTypedName")));
        UUID id = UUID.randomUUID();
        when(studentRepo.findRefsByStudentNoIn(any()))
                .thenReturn(List.of(ref(id, "1000000002", "DbCanonicalName")));
        when(enrollmentRepo.findStudentIdsByLessonId(LESSON)).thenReturn(List.of());

        ImportPreviewResponse r = svc.preview(LESSON, req, TEACHER);
        assertThat(r.willEnroll()).singleElement()
                .extracting("displayName").isEqualTo("DbCanonicalName");   // 用库里的名,防回显错名
    }

    // cancel 幂等

    @Test void cancel_noMatch_idempotentNoThrow() {
        lessonOwned(LessonStatus.ONGOING);
        when(enrollmentRepo.deleteByLessonIdAndStudentId(LESSON, SID_EXIST)).thenReturn(0);
        svc.cancel(LESSON, SID_EXIST, TEACHER);   // 不抛
        verify(enrollmentRepo).deleteByLessonIdAndStudentId(LESSON, SID_EXIST);
    }

    private static void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(code));
    }
}
