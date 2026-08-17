package com.cnsportiot.cloud.security;

import com.cnsportiot.cloud.annotation.RequireAuth;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 声明式角色校验:读取方法/类上的 {@link RequireRole} / {@link RequireAuth},
 * 缺登录抛 UNAUTHORIZED,角色不符抛 FORBIDDEN(经 GlobalExceptionHandler 转信封)。
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }

        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        RequireRole requireRole = hm.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = hm.getBeanType().getAnnotation(RequireRole.class);
        }
        boolean requireAuth = requireRole != null
                || hm.hasMethodAnnotation(RequireAuth.class)
                || hm.getBeanType().isAnnotationPresent(RequireAuth.class);

        if (!requireAuth) {
            return true;
        }

        AuthUser user = currentUser();
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (requireRole != null && !user.hasRole(requireRole.value())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return true;
    }

    private AuthUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth instanceof AuthUserAuthentication a) ? a.getPrincipal() : null;
    }
}

