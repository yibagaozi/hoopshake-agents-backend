package com.cnsportiot.cloud.security;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 拦截 PENDING_ACTIVATION 账号访问业务接口。
 * 放行认证相关路径,其余一律抛 40311。
 */
@Component
public class ActivationCheckInterceptor implements HandlerInterceptor {

    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/auth/login", "/api/auth/logout", "/api/auth/refresh",
            "/api/auth/me", "/api/auth/activate", "/api/auth/register");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getServletPath();
        if (ALLOWED_PATHS.contains(path) || path.startsWith("/api/ingest/")) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AuthUserAuthentication a && !a.getPrincipal().isActivated()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVATED);
        }
        return true;
    }
}
