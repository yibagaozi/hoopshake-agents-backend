package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.auth.RefreshTokenStore;
import com.cnsportiot.cloud.config.RegisterProperties;
import com.cnsportiot.cloud.domain.entity.Account;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.AuthRequests.*;
import com.cnsportiot.cloud.dto.response.AuthDtos.*;
import com.cnsportiot.cloud.repository.AccountRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.security.TokenProvider;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock AccountRepository accountRepository;
    @Mock StudentRepository studentRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TokenProvider tokenProvider;
    @Mock RefreshTokenStore refreshTokenStore;
    @Mock RegisterProperties registerProperties;
    @InjectMocks AuthServiceImpl authService;

    private Account activeTeacher() {
        return Account.createTeacher("teacher1", "T001", "hashed");
    }

    private Account disabledAccount() {
        Account a = activeTeacher();
        a.setStatus(AccountStatus.DISABLED);
        return a;
    }

    private Account pendingAccount() {
        Account a = activeTeacher();
        a.setStatus(AccountStatus.PENDING_ACTIVATION);
        return a;
    }

    private static void setEntityId(Object entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }

    private void stubTokenIssue() {
        given(tokenProvider.issueAccessToken(any())).willReturn("access-token");
        given(tokenProvider.issueRefreshToken(any(), anyString())).willReturn("refresh-token");
        given(tokenProvider.accessTtlSeconds()).willReturn(1800L);
        given(tokenProvider.refreshTtlSeconds()).willReturn(1209600L);
    }

    // ===================== §2.1 Login =====================

    @Nested
    class Login {

        @Test
        void success_byUsername() {
            Account account = activeTeacher();
            given(accountRepository.findByUsername("teacher1")).willReturn(Optional.of(account));
            given(passwordEncoder.matches("pwd", "hashed")).willReturn(true);
            stubTokenIssue();

            TokenResponse resp = authService.login(new LoginRequest("teacher1", "pwd"));

            assertThat(resp.accessToken()).isEqualTo("access-token");
            assertThat(resp.refreshToken()).isEqualTo("refresh-token");
            assertThat(resp.tokenType()).isEqualTo("Bearer");
            assertThat(resp.user().role()).isEqualTo(Role.TEACHER);
            then(refreshTokenStore).should().store(anyString(), any(), any(Duration.class));
        }

        @Test
        void success_byEmail() {
            Account account = activeTeacher();
            account.setEmail("t@x.com");
            given(accountRepository.findByUsername("t@x.com")).willReturn(Optional.empty());
            given(accountRepository.findByEmail("t@x.com")).willReturn(Optional.of(account));
            given(passwordEncoder.matches("pwd", "hashed")).willReturn(true);
            stubTokenIssue();

            TokenResponse resp = authService.login(new LoginRequest("t@x.com", "pwd"));
            assertThat(resp.accessToken()).isNotNull();
        }

        @Test
        void success_byPhone() {
            Account account = activeTeacher();
            account.setPhone("13800000000");
            given(accountRepository.findByUsername("13800000000")).willReturn(Optional.empty());
            given(accountRepository.findByEmail("13800000000")).willReturn(Optional.empty());
            given(accountRepository.findByPhone("13800000000")).willReturn(Optional.of(account));
            given(passwordEncoder.matches("pwd", "hashed")).willReturn(true);
            stubTokenIssue();

            TokenResponse resp = authService.login(new LoginRequest("13800000000", "pwd"));
            assertThat(resp.accessToken()).isNotNull();
        }

        @Test
        void fail_identifierNotFound() {
            given(accountRepository.findByUsername("nobody")).willReturn(Optional.empty());
            given(accountRepository.findByEmail("nobody")).willReturn(Optional.empty());
            given(accountRepository.findByPhone("nobody")).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", "pwd")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.BAD_CREDENTIALS);
        }

        @Test
        void fail_wrongPassword() {
            given(accountRepository.findByUsername("teacher1")).willReturn(Optional.of(activeTeacher()));
            given(passwordEncoder.matches("wrong", "hashed")).willReturn(false);

            assertThatThrownBy(() -> authService.login(new LoginRequest("teacher1", "wrong")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.BAD_CREDENTIALS);
        }

        @Test
        void fail_accountDisabled() {
            Account disabled = disabledAccount();
            given(accountRepository.findByUsername("teacher1")).willReturn(Optional.of(disabled));
            given(passwordEncoder.matches("pwd", "hashed")).willReturn(true);

            assertThatThrownBy(() -> authService.login(new LoginRequest("teacher1", "pwd")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_DISABLED);
        }

        @Test
        void pendingActivation_allowsLogin() {
            Account pending = pendingAccount();
            given(accountRepository.findByUsername("teacher1")).willReturn(Optional.of(pending));
            given(passwordEncoder.matches("pwd", "hashed")).willReturn(true);
            stubTokenIssue();

            TokenResponse resp = authService.login(new LoginRequest("teacher1", "pwd"));
            assertThat(resp.user().status()).isEqualTo(AccountStatus.PENDING_ACTIVATION);
        }
    }

    // ===================== §2.2 Refresh =====================

    @Nested
    class Refresh {

        @Test
        void success_rotatesToken() {
            Claims claims = new DefaultClaims(Map.of(
                    "sub", UUID.randomUUID().toString(), "jti", "old-jti"));
            given(tokenProvider.parseRefreshToken("old-refresh")).willReturn(claims);
            given(refreshTokenStore.isValid("old-jti")).willReturn(true);
            given(accountRepository.findById(any(UUID.class))).willReturn(Optional.of(activeTeacher()));
            stubTokenIssue();

            TokenResponse resp = authService.refresh("old-refresh");

            assertThat(resp.accessToken()).isEqualTo("access-token");
            then(refreshTokenStore).should().revoke("old-jti");
            then(refreshTokenStore).should().store(anyString(), any(), any(Duration.class));
        }

        @Test
        void fail_invalidToken() {
            given(tokenProvider.parseRefreshToken("bad")).willThrow(new RuntimeException("invalid"));

            assertThatThrownBy(() -> authService.refresh("bad"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        @Test
        void fail_revokedJti() {
            Claims claims = new DefaultClaims(Map.of(
                    "sub", UUID.randomUUID().toString(), "jti", "revoked-jti"));
            given(tokenProvider.parseRefreshToken("revoked")).willReturn(claims);
            given(refreshTokenStore.isValid("revoked-jti")).willReturn(false);

            assertThatThrownBy(() -> authService.refresh("revoked"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        @Test
        void fail_accountNotFound() {
            Claims claims = new DefaultClaims(Map.of(
                    "sub", UUID.randomUUID().toString(), "jti", "ok-jti"));
            given(tokenProvider.parseRefreshToken("token")).willReturn(claims);
            given(refreshTokenStore.isValid("ok-jti")).willReturn(true);
            given(accountRepository.findById(any(UUID.class))).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh("token"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        @Test
        void fail_accountDisabledAfterRefresh() {
            Claims claims = new DefaultClaims(Map.of(
                    "sub", UUID.randomUUID().toString(), "jti", "ok-jti"));
            given(tokenProvider.parseRefreshToken("token")).willReturn(claims);
            given(refreshTokenStore.isValid("ok-jti")).willReturn(true);
            given(accountRepository.findById(any(UUID.class))).willReturn(Optional.of(disabledAccount()));

            assertThatThrownBy(() -> authService.refresh("token"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_DISABLED);
        }
    }

    // ===================== §2.3 Logout =====================

    @Nested
    class Logout {

        @Test
        void success_revokesJti() {
            Claims claims = new DefaultClaims(Map.of("jti", "the-jti"));
            given(tokenProvider.parseRefreshToken("rt")).willReturn(claims);

            authService.logout("rt");

            then(refreshTokenStore).should().revoke("the-jti");
        }

        @Test
        void idempotent_invalidTokenNoException() {
            given(tokenProvider.parseRefreshToken("garbage")).willThrow(new RuntimeException());

            assertThatCode(() -> authService.logout("garbage")).doesNotThrowAnyException();
        }
    }

    // ===================== §2.4 Me =====================

    @Nested
    class Me {

        @Test
        void teacher_returnsProfile() {
            UUID accountId = UUID.randomUUID();
            Account account = activeTeacher();
            account.setEmail("t@x.com");
            account.setDisplayName("张老师");
            given(accountRepository.findById(accountId)).willReturn(Optional.of(account));

            AuthUser current = new AuthUser(accountId, "teacher1", Role.TEACHER, null, AccountStatus.ACTIVE);
            UserProfileResponse resp = authService.me(current);

            assertThat(resp.username()).isEqualTo("teacher1");
            assertThat(resp.email()).isEqualTo("t@x.com");
            assertThat(resp.displayName()).isEqualTo("张老师");
            assertThat(resp.studentId()).isNull();
        }

        @Test
        void student_includesStudentInfo() {
            UUID accountId = UUID.randomUUID();
            UUID studentId = UUID.randomUUID();
            Account account = Account.createStudent("stu1", "s@x.com", "138", "hash");
            setEntityId(account, accountId);
            Student student = Student.create(accountId, "S2024001");

            given(accountRepository.findById(accountId)).willReturn(Optional.of(account));
            given(studentRepository.findByAccountId(accountId)).willReturn(Optional.of(student));

            AuthUser current = new AuthUser(accountId, "stu1", Role.STUDENT, studentId, AccountStatus.ACTIVE);
            UserProfileResponse resp = authService.me(current);

            assertThat(resp.role()).isEqualTo(Role.STUDENT);
            assertThat(resp.studentNo()).isEqualTo("S2024001");
        }

        @Test
        void fail_accountNotFound() {
            UUID accountId = UUID.randomUUID();
            given(accountRepository.findById(accountId)).willReturn(Optional.empty());

            AuthUser current = new AuthUser(accountId, "ghost", Role.TEACHER, null, AccountStatus.ACTIVE);
            assertThatThrownBy(() -> authService.me(current))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }
    }

    // ===================== §2.5 Register =====================

    @Nested
    class Register {

        private RegisterRequest validRequest() {
            return new RegisterRequest("newuser", "password123", "新教师", "T999",
                    "new@x.com", "13900000000", "invite123");
        }

        @Test
        void success() {
            given(registerProperties.isEnabled()).willReturn(true);
            given(registerProperties.getInviteCode()).willReturn("invite123");
            given(accountRepository.existsByUsername("newuser")).willReturn(false);
            given(accountRepository.existsByStaffNo("T999")).willReturn(false);
            given(accountRepository.existsByEmail("new@x.com")).willReturn(false);
            given(accountRepository.existsByPhone("13900000000")).willReturn(false);
            given(passwordEncoder.encode("password123")).willReturn("encoded");
            given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            RegisterResponse resp = authService.register(validRequest());

            assertThat(resp.username()).isEqualTo("newuser");
            assertThat(resp.role()).isEqualTo(Role.TEACHER);
            assertThat(resp.staffNo()).isEqualTo("T999");
        }

        @Test
        void success_noInviteCodeRequired() {
            given(registerProperties.isEnabled()).willReturn(true);
            given(registerProperties.getInviteCode()).willReturn(null);
            given(accountRepository.existsByUsername("newuser")).willReturn(false);
            given(accountRepository.existsByStaffNo("T999")).willReturn(false);
            given(accountRepository.existsByEmail("new@x.com")).willReturn(false);
            given(accountRepository.existsByPhone("13900000000")).willReturn(false);
            given(passwordEncoder.encode("password123")).willReturn("encoded");
            given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> authService.register(validRequest())).doesNotThrowAnyException();
        }

        @Test
        void fail_registrationDisabled() {
            given(registerProperties.isEnabled()).willReturn(false);

            assertThatThrownBy(() -> authService.register(validRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.NOT_IMPLEMENTED);
        }

        @Test
        void fail_wrongInviteCode() {
            given(registerProperties.isEnabled()).willReturn(true);
            given(registerProperties.getInviteCode()).willReturn("correct-code");

            assertThatThrownBy(() -> authService.register(validRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        void fail_duplicateUsername() {
            given(registerProperties.isEnabled()).willReturn(true);
            given(registerProperties.getInviteCode()).willReturn(null);
            given(accountRepository.existsByUsername("newuser")).willReturn(true);

            assertThatThrownBy(() -> authService.register(validRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_IDENTIFIER);
        }

        @Test
        void fail_duplicateStaffNo() {
            given(registerProperties.isEnabled()).willReturn(true);
            given(registerProperties.getInviteCode()).willReturn(null);
            given(accountRepository.existsByUsername("newuser")).willReturn(false);
            given(accountRepository.existsByStaffNo("T999")).willReturn(true);

            assertThatThrownBy(() -> authService.register(validRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_IDENTIFIER);
        }

        @Test
        void fail_duplicateEmail() {
            given(registerProperties.isEnabled()).willReturn(true);
            given(registerProperties.getInviteCode()).willReturn(null);
            given(accountRepository.existsByUsername("newuser")).willReturn(false);
            given(accountRepository.existsByStaffNo("T999")).willReturn(false);
            given(accountRepository.existsByEmail("new@x.com")).willReturn(true);

            assertThatThrownBy(() -> authService.register(validRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_IDENTIFIER);
        }

        @Test
        void fail_duplicatePhone() {
            given(registerProperties.isEnabled()).willReturn(true);
            given(registerProperties.getInviteCode()).willReturn(null);
            given(accountRepository.existsByUsername("newuser")).willReturn(false);
            given(accountRepository.existsByStaffNo("T999")).willReturn(false);
            given(accountRepository.existsByEmail("new@x.com")).willReturn(false);
            given(accountRepository.existsByPhone("13900000000")).willReturn(true);

            assertThatThrownBy(() -> authService.register(validRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_IDENTIFIER);
        }

        @Test
        void fail_concurrentDuplicate_dbConstraint() {
            given(registerProperties.isEnabled()).willReturn(true);
            given(registerProperties.getInviteCode()).willReturn(null);
            given(accountRepository.existsByUsername("newuser")).willReturn(false);
            given(accountRepository.existsByStaffNo("T999")).willReturn(false);
            given(accountRepository.existsByEmail("new@x.com")).willReturn(false);
            given(accountRepository.existsByPhone("13900000000")).willReturn(false);
            given(passwordEncoder.encode("password123")).willReturn("encoded");
            given(accountRepository.save(any())).willThrow(new DataIntegrityViolationException("dup"));

            assertThatThrownBy(() -> authService.register(validRequest()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_IDENTIFIER);
        }

        @Test
        void success_nullEmailAndPhone_skipsUniqueCheck() {
            RegisterRequest req = new RegisterRequest("newuser", "password123", "新教师", "T999",
                    null, null, null);
            given(registerProperties.isEnabled()).willReturn(true);
            given(registerProperties.getInviteCode()).willReturn(null);
            given(accountRepository.existsByUsername("newuser")).willReturn(false);
            given(accountRepository.existsByStaffNo("T999")).willReturn(false);
            given(passwordEncoder.encode("password123")).willReturn("encoded");
            given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> authService.register(req)).doesNotThrowAnyException();
            then(accountRepository).should(never()).existsByEmail(any());
            then(accountRepository).should(never()).existsByPhone(any());
        }
    }

    // ===================== §2.6 Activate =====================

    @Nested
    class Activate {

        @Test
        void success_setsPasswordAndPhone() {
            UUID accountId = UUID.randomUUID();
            Account pending = pendingAccount();
            given(accountRepository.findById(accountId)).willReturn(Optional.of(pending));
            given(passwordEncoder.encode("newpwd123")).willReturn("new-hash");
            given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            stubTokenIssue();

            AuthUser current = new AuthUser(accountId, "teacher1", Role.TEACHER, null, AccountStatus.PENDING_ACTIVATION);
            TokenResponse resp = authService.activate(new ActivateRequest("13800000000", "newpwd123"), current);

            assertThat(resp.accessToken()).isEqualTo("access-token");
            assertThat(pending.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(pending.getPhone()).isEqualTo("13800000000");
            assertThat(pending.getPasswordHash()).isEqualTo("new-hash");
        }

        @Test
        void success_noPhone() {
            UUID accountId = UUID.randomUUID();
            Account pending = pendingAccount();
            given(accountRepository.findById(accountId)).willReturn(Optional.of(pending));
            given(passwordEncoder.encode("newpwd123")).willReturn("new-hash");
            given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            stubTokenIssue();

            AuthUser current = new AuthUser(accountId, "teacher1", Role.TEACHER, null, AccountStatus.PENDING_ACTIVATION);
            authService.activate(new ActivateRequest(null, "newpwd123"), current);

            assertThat(pending.getPhone()).isNull();
        }

        @Test
        void fail_alreadyActive() {
            UUID accountId = UUID.randomUUID();
            given(accountRepository.findById(accountId)).willReturn(Optional.of(activeTeacher()));

            AuthUser current = new AuthUser(accountId, "teacher1", Role.TEACHER, null, AccountStatus.ACTIVE);
            assertThatThrownBy(() -> authService.activate(new ActivateRequest(null, "newpwd"), current))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.STATE_CONFLICT);
        }

        @Test
        void fail_duplicatePhone() throws Exception {
            UUID accountId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            Account pending = pendingAccount();
            setEntityId(pending, accountId);
            Account other = activeTeacher();
            setEntityId(other, otherId);

            given(accountRepository.findById(accountId)).willReturn(Optional.of(pending));
            given(accountRepository.existsByPhone("13800000000")).willReturn(true);
            given(accountRepository.findByPhone("13800000000")).willReturn(Optional.of(other));

            AuthUser current = new AuthUser(accountId, "teacher1", Role.TEACHER, null, AccountStatus.PENDING_ACTIVATION);
            assertThatThrownBy(() -> authService.activate(new ActivateRequest("13800000000", "newpwd"), current))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_IDENTIFIER);
        }
    }
}

