package com.cnsportiot.cloud.security;

import com.cnsportiot.cloud.exception.ErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 解析 {@code Authorization: Bearer <accessToken>},成功则填充 SecurityContext。
 * - 无 header:放行,交由授权层对受保护路径返回 401(EntryPoint);
 * - token 过期:直接写 {@code TOKEN_EXPIRED} 信封并中断(前端据此静默刷新);
 * - token 无效:直接写 {@code TOKEN_INVALID} 信封并中断。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final TokenProvider tokenProvider;
    private final ApiResponseWriter writer;

    public JwtAuthFilter(TokenProvider tokenProvider, ApiResponseWriter writer) {
        this.tokenProvider = tokenProvider;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            try {
                AuthUser user = tokenProvider.parseAccessToken(token);
                var authentication = new AuthUserAuthentication(user);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ExpiredJwtException e) {
                writer.writeError(response, ErrorCode.TOKEN_EXPIRED);
                return;
            } catch (Exception e) {
                writer.writeError(response, ErrorCode.TOKEN_INVALID);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** ingest 走服务间鉴权,不解析用户 JWT */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().startsWith("/api/ingest/");
    }
}

