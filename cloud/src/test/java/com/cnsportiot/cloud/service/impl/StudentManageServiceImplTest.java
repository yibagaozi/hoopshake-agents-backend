package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.config.StudentProperties;
import com.cnsportiot.cloud.domain.entity.Account;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.dto.request.StudentManageRequests.RegisterStudentRequest;
import com.cnsportiot.cloud.dto.request.StudentManageRequests.UpdateStudentRequest;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.RegisterStudentResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentDetailResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentStatsResponse;
import com.cnsportiot.cloud.repository.*;
import com.cnsportiot.contracts.enums.DominantHand;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** StudentManageServiceImpl 单测:建档去重/默认/审计、详情 gallery 可空、增量更新、统计 */
class StudentManageServiceImplTest {

    private static final UUID SID = UUID.fromString("33333333-0000-0000-0000-000000000001");
    private static final UUID ACC = UUID.fromString("44444444-0000-0000-0000-000000000001");
    private static final String OPERATOR = "55555555-0000-0000-0000-000000000001";

    private AccountRepository accountRepo;
    private StudentRepository studentRepo;
    private ActionClipRepository clipRepo;
    private TrainingSessionRepository sessionRepo;
    private AuditLogRepository auditRepo;
    private PasswordEncoder encoder;
    private StudentProperties studentProps;
    private StudentManageServiceImpl svc;

    @BeforeEach
    void setup() {
        accountRepo = mock(AccountRepository.class);
        studentRepo = mock(StudentRepository.class);
        clipRepo = mock(ActionClipRepository.class);
        sessionRepo = mock(TrainingSessionRepository.class);
        auditRepo = mock(AuditLogRepository.class);
        encoder = mock(PasswordEncoder.class);
        studentProps = new StudentProperties();
        svc = new StudentManageServiceImpl(accountRepo, studentRepo, clipRepo, sessionRepo, auditRepo, encoder, studentProps);
        lenient().when(accountRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(studentRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private RegisterStudentRequest reg(String studentNo, String username, String password) {
        return new RegisterStudentRequest(studentNo, "小明", username, password,
                null, null, DominantHand.RIGHT, null, null, "U12");
    }

    // registerStudent

    @Test void register_duplicateStudentNo_conflict() {
        when(studentRepo.existsByStudentNo("2024001234")).thenReturn(true);
        assertBusiness(() -> svc.registerStudent(reg("2024001234", null, null), OPERATOR),
                ErrorCode.DUPLICATE_IDENTIFIER);
    }

    @Test void register_defaultsUsernameToStudentNo_andGeneratesPassword_writesAudit() {
        stubNoDuplicates();
        when(encoder.encode("2024001234")).thenReturn("ENC");   // 初始密码=学号
        ArgumentCaptor<Account> cap = ArgumentCaptor.forClass(Account.class);
        when(accountRepo.save(cap.capture())).thenAnswer(i -> i.getArgument(0));

        RegisterStudentResponse r = svc.registerStudent(reg("2024001234", null, null), OPERATOR);

        assertThat(r.studentNo()).isEqualTo("2024001234");
        assertThat(r.username()).isEqualTo("2024001234");    // 默认用户名=学号
        assertThat(r.initialPassword()).isTrue();            // password 为空 → 系统生成
        Account acc = cap.getValue();
        assertThat(acc.getUsername()).isEqualTo("2024001234");
        assertThat(acc.getStatus()).isEqualTo(AccountStatus.PENDING_ACTIVATION);
        assertThat(acc.getPasswordHash()).isEqualTo("ENC");
        verify(auditRepo).save(any());                       // 建档写审计
    }

    @Test void register_explicitUsernameAndPassword_flagFalse() {
        stubNoDuplicates();
        when(encoder.encode("secret6")).thenReturn("ENC");
        RegisterStudentResponse r = svc.registerStudent(reg("2024001234", "user1", "secret6"), OPERATOR);
        assertThat(r.username()).isEqualTo("user1");
        assertThat(r.initialPassword()).isFalse();           // 显式给了密码
        verify(encoder).encode("secret6");
    }

    @Test void register_uniqueViolationOnSave_conflict() {
        stubNoDuplicates();
        when(encoder.encode(any())).thenReturn("ENC");
        when(accountRepo.save(any())).thenThrow(new DataIntegrityViolationException("uk"));
        assertBusiness(() -> svc.registerStudent(reg("2024001234", null, null), OPERATOR),
                ErrorCode.DUPLICATE_IDENTIFIER);
    }

    // getStudentDetail

    @Test void detail_notFoundOrInactive_notFound() {
        when(studentRepo.findActiveDetailByStudentId(SID)).thenReturn(Optional.empty());
        assertBusiness(() -> svc.getStudentDetail(SID), ErrorCode.NOT_FOUND);
    }

    @Test void detail_noGallery_activeGalleryNull() {
        StudentRepository.StudentDetail d = mock(StudentRepository.StudentDetail.class);
        when(d.getStudentId()).thenReturn(SID);
        when(d.getGalleryId()).thenReturn(null);   // 无特征库
        when(studentRepo.findActiveDetailByStudentId(SID)).thenReturn(Optional.of(d));
        StudentDetailResponse r = svc.getStudentDetail(SID);
        assertThat(r.activeGallery()).isNull();
    }

    @Test void detail_withGallery_summaryMapped() {
        StudentRepository.StudentDetail d = mock(StudentRepository.StudentDetail.class);
        when(d.getStudentId()).thenReturn(SID);
        UUID gid = UUID.randomUUID();
        when(d.getGalleryId()).thenReturn(gid);
        when(d.getGalleryVersion()).thenReturn(2);
        when(d.getGalleryStatus()).thenReturn("ACTIVE");
        when(studentRepo.findActiveDetailByStudentId(SID)).thenReturn(Optional.of(d));
        StudentDetailResponse r = svc.getStudentDetail(SID);
        assertThat(r.activeGallery()).isNotNull();
        assertThat(r.activeGallery().version()).isEqualTo(2);
    }

    @Test void detail_nullGalleryVersion_defaultsToZero() {
        StudentRepository.StudentDetail d = mock(StudentRepository.StudentDetail.class);
        when(d.getStudentId()).thenReturn(SID);
        when(d.getGalleryId()).thenReturn(UUID.randomUUID());
        when(d.getGalleryVersion()).thenReturn(null);   // 版本缺失 → 0
        when(d.getGalleryStatus()).thenReturn("ACTIVE");
        when(studentRepo.findActiveDetailByStudentId(SID)).thenReturn(Optional.of(d));
        assertThat(svc.getStudentDetail(SID).activeGallery().version()).isZero();
    }

    // updateStudent

    @Test void update_notFound() {
        when(studentRepo.findById(SID)).thenReturn(Optional.empty());
        assertBusiness(() -> svc.updateStudent(SID, new UpdateStudentRequest(null, null, null, null, null)),
                ErrorCode.NOT_FOUND);
    }

    @Test void update_displayName_updatesAccount_otherFieldsIncremental() {
        Student s = mock(Student.class);
        when(s.getAccountId()).thenReturn(ACC);
        when(studentRepo.findById(SID)).thenReturn(Optional.of(s));
        Account acc = mock(Account.class);
        when(accountRepo.findById(ACC)).thenReturn(Optional.of(acc));
        StudentRepository.StudentDetail d = mock(StudentRepository.StudentDetail.class);
        when(d.getStudentId()).thenReturn(SID);
        when(studentRepo.findActiveDetailByStudentId(SID)).thenReturn(Optional.of(d));

        svc.updateStudent(SID, new UpdateStudentRequest("新名", DominantHand.LEFT, new BigDecimal("175.0"), null, null));
        verify(acc).setDisplayName("新名");
        verify(s).setDominantHand(DominantHand.LEFT);
        verify(s).setHeightCm(new BigDecimal("175.0"));
        verify(s, never()).setLegLengthCm(any());   // null 不设
        verify(s, never()).setGradeBand(any());
    }

    // getStudentStats

    @Test void stats_notFound() {
        when(studentRepo.findById(SID)).thenReturn(Optional.empty());
        assertBusiness(() -> svc.getStudentStats(SID), ErrorCode.NOT_FOUND);
    }

    @Test void stats_happy_aggregatesCountsAndName() {
        Student s = mock(Student.class);
        when(s.getAccountId()).thenReturn(ACC);
        when(studentRepo.findById(SID)).thenReturn(Optional.of(s));
        Account acc = mock(Account.class);
        when(acc.getDisplayName()).thenReturn("小明");
        when(accountRepo.findById(ACC)).thenReturn(Optional.of(acc));
        when(clipRepo.findActionStatsByStudentId(SID)).thenReturn(List.of());
        when(clipRepo.countByStudentId(SID)).thenReturn(42L);
        when(clipRepo.countDistinctSessionByStudentId(SID)).thenReturn(5L);
        when(sessionRepo.findLastRecordedAtByStudentId(SID)).thenReturn(Optional.empty());

        StudentStatsResponse r = svc.getStudentStats(SID);
        assertThat(r.totalClips()).isEqualTo(42);
        assertThat(r.totalSessions()).isEqualTo(5);
        assertThat(r.displayName()).isEqualTo("小明");
        assertThat(r.lastSessionAt()).isNull();
        assertThat(r.byAction()).isEmpty();
    }

    // helpers

    private void stubNoDuplicates() {
        lenient().when(studentRepo.existsByStudentNo(any())).thenReturn(false);
        lenient().when(accountRepo.existsByUsername(any())).thenReturn(false);
        lenient().when(accountRepo.existsByEmail(any())).thenReturn(false);
        lenient().when(accountRepo.existsByPhone(any())).thenReturn(false);
    }

    private static void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(code));
    }
}

