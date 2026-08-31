package com.cnsportiot.cloud.security;

import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.domain.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class TokenProviderTest {

    private TokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-key-must-be-at-least-32-bytes-long!");
        props.setIssuer("test-issuer");
        props.setAccessTtl(Duration.ofMinutes(30));
        props.setRefreshTtl(Duration.ofDays(14));
        tokenProvider = new TokenProvider(props);
    }

    @Nested
    class AccessToken {

        private final AuthUser teacher = new AuthUser(
                UUID.randomUUID(), "teacher1", Role.TEACHER, null, AccountStatus.ACTIVE);

        private final UUID studentId = UUID.randomUUID();
        private final AuthUser student = new AuthUser(
                UUID.randomUUID(), "student1", Role.STUDENT, studentId, AccountStatus.ACTIVE);

        @Test
        void issueAndParse_teacher() {
            String token = tokenProvider.issueAccessToken(teacher);
            AuthUser parsed = tokenProvider.parseAccessToken(token);

            assertThat(parsed.accountId()).isEqualTo(teacher.accountId());
            assertThat(parsed.username()).isEqualTo("teacher1");
            assertThat(parsed.role()).isEqualTo(Role.TEACHER);
            assertThat(parsed.studentId()).isNull();
            assertThat(parsed.status()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        void issueAndParse_studentWithId() {
            String token = tokenProvider.issueAccessToken(student);
            AuthUser parsed = tokenProvider.parseAccessToken(token);

            assertThat(parsed.studentId()).isEqualTo(studentId);
            assertThat(parsed.role()).isEqualTo(Role.STUDENT);
        }

        @Test
        void issueAndParse_pendingActivationStatus() {
            AuthUser pending = new AuthUser(
                    UUID.randomUUID(), "pending", Role.TEACHER, null, AccountStatus.PENDING_ACTIVATION);
            String token = tokenProvider.issueAccessToken(pending);

            assertThat(tokenProvider.parseAccessToken(token).status())
                    .isEqualTo(AccountStatus.PENDING_ACTIVATION);
        }

        @Test
        void parse_invalidSignature_throws() {
            JwtProperties otherProps = new JwtProperties();
            otherProps.setSecret("another-secret-key-must-also-be-at-least-32-bytes!");
            otherProps.setIssuer("test-issuer");
            TokenProvider otherProvider = new TokenProvider(otherProps);

            String token = otherProvider.issueAccessToken(teacher);
            assertThatThrownBy(() -> tokenProvider.parseAccessToken(token))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        void parse_expiredToken_throws() {
            JwtProperties expiredProps = new JwtProperties();
            expiredProps.setSecret("test-secret-key-must-be-at-least-32-bytes-long!");
            expiredProps.setIssuer("test-issuer");
            expiredProps.setAccessTtl(Duration.ofMillis(-1));
            TokenProvider expiredProvider = new TokenProvider(expiredProps);

            String token = expiredProvider.issueAccessToken(teacher);
            assertThatThrownBy(() -> tokenProvider.parseAccessToken(token))
                    .isInstanceOf(ExpiredJwtException.class);
        }

        @Test
        void parse_refreshTokenAsAccess_throws() {
            String refresh = tokenProvider.issueRefreshToken(teacher.accountId(), "jti-1");
            assertThatThrownBy(() -> tokenProvider.parseAccessToken(refresh))
                    .isInstanceOf(JwtException.class)
                    .hasMessageContaining("非 access token");
        }
    }

    @Nested
    class RefreshToken {

        private final UUID accountId = UUID.randomUUID();

        @Test
        void issueAndParse() {
            String jti = "test-jti-123";
            String token = tokenProvider.issueRefreshToken(accountId, jti);
            Claims claims = tokenProvider.parseRefreshToken(token);

            assertThat(claims.getSubject()).isEqualTo(accountId.toString());
            assertThat(claims.getId()).isEqualTo(jti);
        }

        @Test
        void parse_accessTokenAsRefresh_throws() {
            AuthUser user = new AuthUser(accountId, "u", Role.TEACHER, null, AccountStatus.ACTIVE);
            String access = tokenProvider.issueAccessToken(user);
            assertThatThrownBy(() -> tokenProvider.parseRefreshToken(access))
                    .isInstanceOf(JwtException.class)
                    .hasMessageContaining("非 refresh token");
        }
    }

    @Test
    void ttlSeconds_matchProperties() {
        assertThat(tokenProvider.accessTtlSeconds()).isEqualTo(1800L);
        assertThat(tokenProvider.refreshTtlSeconds()).isEqualTo(14 * 86400L);
    }
}

