package com.cnsportiot.cloud.security;

import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.domain.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** access / refresh token 的签发与解析 是否被撤销由 AuthAppService 查 Redis 判定 */
@Component
public class TokenProvider {

    private static final String TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final JwtProperties props;
    private final SecretKey key;

    public TokenProvider(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(user.accountId().toString())
                .claim(TYPE, TYPE_ACCESS)
                .claim("username", user.username())
                .claim("role", user.role().name())
                .claim("studentId", user.studentId() == null ? null : user.studentId().toString())
                .claim("status", user.status().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.getAccessTtl())))
                .signWith(key)
                .compact();
    }

    /** 返回 refresh token;jti 由调用方生成并同时写入 Redis(白名单/可撤销) */
    public String issueRefreshToken(UUID accountId, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(accountId.toString())
                .id(jti)
                .claim(TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.getRefreshTtl())))
                .signWith(key)
                .compact();
    }

    public long accessTtlSeconds() { return props.getAccessTtl().toSeconds(); }
    public long refreshTtlSeconds() { return props.getRefreshTtl().toSeconds(); }

    /**
     * 解析并校验 access token
     * @throws io.jsonwebtoken.ExpiredJwtException 过期 上层映射 TOKEN_EXPIRED
     * @throws io.jsonwebtoken.JwtException 其余无效 上层映射 TOKEN_INVALID
     */
    public AuthUser parseAccessToken(String token) {
        Claims c = parse(token);
        if (!TYPE_ACCESS.equals(c.get(TYPE, String.class))) {
            throw new io.jsonwebtoken.JwtException("非 access token");
        }
        String studentId = c.get("studentId", String.class);
        String statusStr = c.get("status", String.class);
        AccountStatus status = statusStr != null ? AccountStatus.valueOf(statusStr) : AccountStatus.ACTIVE;
        return new AuthUser(
                UUID.fromString(c.getSubject()),
                c.get("username", String.class),
                Role.valueOf(c.get("role", String.class)),
                studentId == null ? null : UUID.fromString(studentId),
                status);
    }

    /** 解析 refresh token,返回其 Claims */
    public Claims parseRefreshToken(String token) {
        Claims c = parse(token);
        if (!TYPE_REFRESH.equals(c.get(TYPE, String.class))) {
            throw new io.jsonwebtoken.JwtException("非 refresh token");
        }
        return c;
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

