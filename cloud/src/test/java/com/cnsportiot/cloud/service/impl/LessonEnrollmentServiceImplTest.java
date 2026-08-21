package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.dto.request.EnrollmentRequests.ImportEnrollmentRequest;
import com.cnsportiot.cloud.dto.request.EnrollmentRequests.StudentEntry;
import com.cnsportiot.cloud.dto.response.EnrollmentDtos.EnrollmentItem;
import com.cnsportiot.cloud.dto.response.EnrollmentDtos.ImportPreviewResponse;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.cloud.service.StudentProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
/**
 * §5.6 ~ §5.11 参课名单与导入预检：覆盖名单列表、批量导入、去重、并发建档和预检分流逻辑。
 */
class LessonEnrollmentServiceImplTest {

    @Mock
    private LessonRepository lessonRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private LessonEnrollmentRepository enrollmentRepository;

    @Mock
    private StudentProvisioningService studentProvisioning;

    @InjectMocks
    private LessonEnrollmentServiceImpl service;

    private Lesson lesson(UUID lessonId, UUID teacherId, LessonStatus status) {
        Lesson lesson = Lesson.builder()
                .teacherId(teacherId)
                .title("Math")
                .actionTypes(List.of("shot"))
                .enabledCheckpoints(List.of())
                .status(status)
                .build();
        ReflectionTestUtils.setField(lesson, "id", lessonId);
        return lesson;
    }

    private LessonEnrollmentRepository.EnrollmentView enrollmentView(UUID studentId, String studentNo, String displayName, boolean galleryReady) {
        LessonEnrollmentRepository.EnrollmentView view = org.mockito.Mockito.mock(LessonEnrollmentRepository.EnrollmentView.class);
        org.mockito.Mockito.when(view.getStudentId()).thenReturn(studentId);
        org.mockito.Mockito.when(view.getStudentNo()).thenReturn(studentNo);
        org.mockito.Mockito.when(view.getDisplayName()).thenReturn(displayName);
        org.mockito.Mockito.when(view.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        org.mockito.Mockito.when(view.getGalleryReady()).thenReturn(galleryReady);
        org.mockito.Mockito.when(view.getEnrolledAt()).thenReturn(OffsetDateTime.parse("2026-08-21T09:00:00Z"));
        return view;
    }

    private StudentRepository.StudentRef studentRef(UUID studentId, String studentNo, String displayName) {
        StudentRepository.StudentRef ref = org.mockito.Mockito.mock(StudentRepository.StudentRef.class);
        org.mockito.Mockito.when(ref.getStudentId()).thenReturn(studentId);
        org.mockito.Mockito.when(ref.getStudentNo()).thenReturn(studentNo);
        org.mockito.Mockito.when(ref.getDisplayName()).thenReturn(displayName);
        return ref;
    }

    // §5.6 参课名单：只返回当前课程归属教师可见的报名记录。
    @Test
    void list_returnsEnrollmentItems() {
        UUID lessonId = UUID.randomUUID();
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();

        given(lessonRepository.findById(lessonId)).willReturn(Optional.of(lesson(lessonId, teacherId, LessonStatus.PLANNED)));
        given(enrollmentRepository.findEnrollmentView(lessonId)).willReturn(List.of(enrollmentView(studentId, "1234567890", "Alice", true)));

        List<EnrollmentItem> result = service.list(lessonId, teacherId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).studentNo()).isEqualTo("1234567890");
        assertThat(result.get(0).displayName()).isEqualTo("Alice");
        assertThat(result.get(0).galleryReady()).isTrue();
    }

    // §5.7 批量导入名单：同学号去重，已存在学生直接报名，未存在学生需先建档再报名，并保留 justCreated 标记。
    @Test
    void importStudents_createsNewStudentAndEnrolls() {
        UUID lessonId = UUID.randomUUID();
        UUID teacherId = UUID.randomUUID();
        UUID newStudentId = UUID.randomUUID();
        UUID existingStudentId = UUID.randomUUID();

        given(lessonRepository.findById(lessonId)).willReturn(Optional.of(lesson(lessonId, teacherId, LessonStatus.PLANNED)));
        given(studentRepository.findRefsByStudentNoIn(anyList())).willReturn(List.of(studentRef(existingStudentId, "0987654321", "Bob")));
        given(studentProvisioning.provisionByStudentNo("1234567890", "Alice")).willReturn(newStudentId);
        given(enrollmentRepository.enrollIfAbsent(lessonId, newStudentId)).willReturn(1);
        given(enrollmentRepository.enrollIfAbsent(lessonId, existingStudentId)).willReturn(1);
        given(enrollmentRepository.findEnrollmentView(lessonId)).willReturn(List.of(
                enrollmentView(newStudentId, "1234567890", "Alice", false),
                enrollmentView(existingStudentId, "0987654321", "Bob", true)
        ));

        var request = new ImportEnrollmentRequest(List.of(
                new StudentEntry("1234567890", "Alice"),
                new StudentEntry("1234567890", "Alice Again"),
                new StudentEntry("0987654321", "Bob")
        ));

        List<EnrollmentItem> result = service.importStudents(lessonId, request, teacherId);

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(item -> {
            assertThat(item.studentId()).isEqualTo(newStudentId);
            assertThat(item.justCreated()).isTrue();
        });
        assertThat(result).anySatisfy(item -> {
            assertThat(item.studentId()).isEqualTo(existingStudentId);
            assertThat(item.justCreated()).isFalse();
        });
        then(studentProvisioning).should().provisionByStudentNo("1234567890", "Alice");
    }

    // §5.11 导入预检：只读校验和分桶，不写库，要求 invalid / willCreate / willEnroll / alreadyEnrolled 四类互斥。
    @Test
    void preview_groupsExistingAndInvalidStudents() {
        UUID lessonId = UUID.randomUUID();
        UUID teacherId = UUID.randomUUID();
        UUID alreadyEnrolledId = UUID.randomUUID();
        UUID willEnrollId = UUID.randomUUID();

        given(lessonRepository.findById(lessonId)).willReturn(Optional.of(lesson(lessonId, teacherId, LessonStatus.PLANNED)));
        given(studentRepository.findRefsByStudentNoIn(anyList())).willReturn(List.of(
                studentRef(alreadyEnrolledId, "1111111111", "Alice"),
                studentRef(willEnrollId, "2222222222", "Bob")
        ));
        given(enrollmentRepository.findStudentIdsByLessonId(lessonId)).willReturn(List.of(alreadyEnrolledId));

        var request = new ImportEnrollmentRequest(List.of(
                new StudentEntry("1111111111", "Alice"),
                new StudentEntry("2222222222", "Bob"),
                new StudentEntry("bad", "Nope")
        ));

        ImportPreviewResponse result = service.preview(lessonId, request, teacherId);

        assertThat(result.willCreate()).isEmpty();
        assertThat(result.willEnroll()).hasSize(1);
        assertThat(result.willEnroll().get(0).studentNo()).isEqualTo("2222222222");
        assertThat(result.alreadyEnrolled()).hasSize(1);
        assertThat(result.alreadyEnrolled().get(0).studentNo()).isEqualTo("1111111111");
        assertThat(result.invalid()).hasSize(1);
        assertThat(result.invalid().get(0).studentNo()).isEqualTo("bad");
    }
}
