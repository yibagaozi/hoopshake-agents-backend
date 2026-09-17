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
 * 校验 {@code X-Service-Token}(边缘/算法/采集服务共享密钥),成功则置一个 ROLE_SERVICE 的authentication 以通过授权层。
 * 强制({@code /api/ingest/**}):没带或带错直接写 {@code SERVICE_TOKEN_INVALID} 并短路;
 * 可选({@code /actuator/prometheus}、{@code /actuator/metrics/**}):带对了就认成 SERVICE,否则放行给后面的 JWT 过滤器,由授权层决定
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
        boolean valid = expectedToken != null && !expectedToken.isBlank() && expectedToken.equals(token);

        if (valid) {
            SecurityContextHolder.getContext().setAuthentication(new ServiceAuthentication());
            chain.doFilter(request, response);
            return;
        }
        if (isMetrics(request)) {
            // 可选模式:没带服务令牌不代表越权,可能是管理员拿 JWT 来看;交给后续过滤器与授权层
            chain.doFilter(request, response);
            return;
        }
        writer.writeError(response, ErrorCode.SERVICE_TOKEN_INVALID);
    }

    /** 只处理 ingest 与指标端点 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/ingest/") && !isMetrics(request);
    }

    /** 指标端点:Prometheus 用服务令牌抓取,管理员用 JWT 查看 */
    private static boolean isMetrics(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/actuator/prometheus") || path.startsWith("/actuator/metrics");
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

