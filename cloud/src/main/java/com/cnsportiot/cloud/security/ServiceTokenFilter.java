package com.cnsportiot.cloud.security;

import com.cnsportiot.contracts.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 校验 {@code /api/ingest/**} 的 {@code X-Service-Token}(边缘/算法服务共享密钥)
 * 失败写 {@code SERVICE_TOKEN_INVALID};成功置一个 ROLE_SERVICE 的 authentication 以通过授权层
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Service-Token";

    private final String expectedToken;
    private final ApiResponseWriter writer;

    public ServiceTokenFilter(@Value("${hoopshake.ingest.service-token}") String expectedToken,
                              ApiResponseWriter writer) {
        this.expectedToken = expectedToken;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (expectedToken == null || expectedToken.isBlank() || !expectedToken.equals(token)) {
            writer.writeError(response, ErrorCode.SERVICE_TOKEN_INVALID);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new ServiceAuthentication());
        chain.doFilter(request, response);
    }

    /** ingest 前缀 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/ingest/");
    }

    /** 服务间调用的 authentication 占位 */
    private static final class ServiceAuthentication extends AbstractAuthenticationToken {
        ServiceAuthentication() {
            super(List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            setAuthenticated(true);
        }
        @Override public Object getCredentials() { return null; }
        @Override public Object getPrincipal() { return "service"; }
    }
}

