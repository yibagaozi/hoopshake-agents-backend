package com.cnsportiot.cloud.security;

import com.cnsportiot.cloud.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 已认证但被 Spring Security 授权层拒绝 FORBIDDEN 信封 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiResponseWriter writer;

    public RestAccessDeniedHandler(ApiResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        writer.writeError(response, ErrorCode.FORBIDDEN);
    }
}
