package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.auth.RefreshTokenStore;
import com.cnsportiot.cloud.config.RegisterProperties;
import com.cnsportiot.cloud.domain.entity.Account;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.repository.AccountRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.cloud.dto.response.AuthDtos.TokenResponse;
import com.cnsportiot.cloud.dto.response.AuthDtos.UserProfileResponse;
import com.cnsportiot.cloud.dto.request.AuthRequests.RegisterRequest;
import com.cnsportiot.cloud.dto.response.AuthDtos.RegisterResponse;
import com.cnsportiot.cloud.dto.response.AuthDtos.UserSummary;
import com.cnsportiot.cloud.dto.request.AuthRequests.ActivateRequest;
import com.cnsportiot.cloud.dto.request.AuthRequests.LoginRequest;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.security.TokenProvider;
import com.cnsportiot.cloud.service.AuthService;
import io.jsonwebtoken.Claims;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private final AccountRepository accountRepository;
    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final RegisterProperties registerProperties;

    public AuthServiceImpl(AccountRepository accountRepository,
                              StudentRepository studentRepository,
                              PasswordEncoder passwordEncoder,
                              TokenProvider tokenProvider,
                              RefreshTokenStore refreshTokenStore,
                              RegisterProperties registerProperties) {
        this.accountRepository = accountRepository;
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.registerProperties = registerProperties;
    }

    @Override
    public TokenResponse login(LoginRequest request) {
        Account account = resolveByIdentifier(request.identifier())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }
        ensureActive(account);

        return issueTokens(account);
    }

    @Override
    public TokenResponse refresh(String refreshToken) {
        Claims claims;
        try {
            claims = tokenProvider.parseRefreshToken(refreshToken);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        String jti = claims.getId();
        if (jti == null || !refreshTokenStore.isValid(jti)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        // 轮换:旧 refresh 立即失效
        refreshTokenStore.revoke(jti);

        UUID accountId = UUID.fromString(claims.getSubject());
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        ensureActive(account);

        return issueTokens(account);
    }

    @Override
    public void logout(String refreshToken) {
        // 幂等:token 无法解析或已失效均视为登出成功
        try {
            Claims claims = tokenProvider.parseRefreshToken(refreshToken);
            if (claims.getId() != null) {
                refreshTokenStore.revoke(claims.getId());
            }
        } catch (Exception ignored) {
            // no-op
        }
    }

    @Override
    public UserProfileResponse me(AuthUser current) {
        Account account = accountRepository.findById(current.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        Student student = account.getRole() == Role.STUDENT
                ? studentRepository.findByAccountId(account.getId()).orElse(null)
                : null;

        return new UserProfileResponse(
                account.getId(),
                account.getUsername(),
                account.getEmail(),
                account.getPhone(),
                account.getRole(),
                account.getStatus(),
                account.getStaffNo(),
                student == null ? null : student.getId(),
                student == null ? null : student.getStudentNo(),
                account.getDisplayName());
    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        // 开关:关闭时视为功能未开放
        if (!registerProperties.isEnabled()) {
            throw new BusinessException(ErrorCode.NOT_IMPLEMENTED, "当前未开放自助注册,请联系管理员开户");
        }

        // 邀请码:服务端配置了才校验
        String expected = registerProperties.getInviteCode();
        if (expected != null && !expected.isBlank() && !expected.equals(request.inviteCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "邀请码无效");
        }

        // 唯一性预检(DB 唯一约束仍是最终防线,并发下靠它兜底)
        if (accountRepository.existsByUsername(request.username())) {
            throw new BusinessException(ErrorCode.DUPLICATE_IDENTIFIER, "用户名已存在");
        }
        if (accountRepository.existsByStaffNo(request.staffNo())) {
            throw new BusinessException(ErrorCode.DUPLICATE_IDENTIFIER, "工号已存在");
        }
        if (request.email() != null && !request.email().isBlank()
                && accountRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_IDENTIFIER, "邮箱已被使用");
        }
        if (request.phone() != null && !request.phone().isBlank()
                && accountRepository.existsByPhone(request.phone())) {
            throw new BusinessException(ErrorCode.DUPLICATE_IDENTIFIER, "手机号已被使用");
        }

        // 建账号:密码必须 BCrypt 编码后入库
        Account account = Account.createTeacher(
                request.username(), request.staffNo(), passwordEncoder.encode(request.password()));
        account.setEmail(emptyToNull(request.email()));
        account.setPhone(emptyToNull(request.phone()));
        account.setDisplayName(request.displayName());

        try {
            accountRepository.save(account);
        } catch (DataIntegrityViolationException e) {     // 并发撞唯一约束
            throw new BusinessException(ErrorCode.DUPLICATE_IDENTIFIER, "登录标识或工号已存在");
        }

        return new RegisterResponse(
                account.getId(), account.getUsername(),
                account.getRole(), account.getStatus(), account.getStaffNo());
    }

    @Override
    @Transactional
    public TokenResponse activate(ActivateRequest request, AuthUser current) {
        Account account = accountRepository.findById(current.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (account.getStatus() != AccountStatus.PENDING_ACTIVATION) {
            throw BusinessException.stateConflict("账号已激活,无需重复操作");
        }

        if (request.phone() != null && !request.phone().isBlank()) {
            if (accountRepository.existsByPhone(request.phone())
                    && !account.getId().equals(accountRepository.findByPhone(request.phone()).map(Account::getId).orElse(null))) {
                throw new BusinessException(ErrorCode.DUPLICATE_IDENTIFIER, "手机号已被使用");
            }
            account.setPhone(request.phone());
        }

        account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        account.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);

        return issueTokens(account);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private Optional<Account> resolveByIdentifier(String identifier) {
        return accountRepository.findByUsername(identifier)
                .or(() -> accountRepository.findByEmail(identifier))
                .or(() -> accountRepository.findByPhone(identifier));
    }

    private void ensureActive(Account account) {
        if (account.getStatus() == AccountStatus.DISABLED) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
    }

    /** 签发双 token,并把新 refresh 的 jti 记入存储 */
    private TokenResponse issueTokens(Account account) {
        Student student = account.getRole() == Role.STUDENT
                ? studentRepository.findByAccountId(account.getId()).orElse(null)
                : null;

        UUID studentId = student == null ? null : student.getId();
        AuthUser authUser = new AuthUser(account.getId(), account.getUsername(), account.getRole(), studentId, account.getStatus());

        String accessToken = tokenProvider.issueAccessToken(authUser);

        String jti = UUID.randomUUID().toString();
        String refreshToken = tokenProvider.issueRefreshToken(account.getId(), jti);
        refreshTokenStore.store(jti, account.getId(), Duration.ofSeconds(tokenProvider.refreshTtlSeconds()));

        UserSummary user = new UserSummary(
                account.getId(),
                account.getUsername(),
                account.getRole(),
                account.getStatus(),
                studentId,
                student == null ? null : student.getStudentNo(),
                account.getDisplayName());

        return new TokenResponse(
                accessToken,
                refreshToken,
                "Bearer",
                tokenProvider.accessTtlSeconds(),
                tokenProvider.refreshTtlSeconds(),
                user);
    }
}

