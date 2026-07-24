package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.auth.RefreshTokenStore;
import com.cnsportiot.cloud.domain.entity.Account;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.repository.AccountRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.cloud.dto.response.AuthDtos.TokenResponse;
import com.cnsportiot.cloud.dto.response.AuthDtos.UserProfileResponse;
import com.cnsportiot.cloud.dto.response.AuthDtos.UserSummary;
import com.cnsportiot.cloud.dto.request.AuthRequests.LoginRequest;
import com.cnsportiot.cloud.exception.BusinessException;
import com.cnsportiot.cloud.exception.ErrorCode;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.security.TokenProvider;
import com.cnsportiot.cloud.service.AuthService;
import io.jsonwebtoken.Claims;
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

    public AuthServiceImpl(AccountRepository accountRepository,
                              StudentRepository studentRepository,
                              PasswordEncoder passwordEncoder,
                              TokenProvider tokenProvider,
                              RefreshTokenStore refreshTokenStore) {
        this.accountRepository = accountRepository;
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
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
                student == null ? null : student.getDisplayName());
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
        AuthUser authUser = new AuthUser(account.getId(), account.getUsername(), account.getRole(), studentId);

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
                student == null ? null : student.getDisplayName());

        return new TokenResponse(
                accessToken,
                refreshToken,
                "Bearer",
                tokenProvider.accessTtlSeconds(),
                tokenProvider.refreshTtlSeconds(),
                user);
    }
}

