package com.cnsportiot.cloud.security;

import com.cnsportiot.contracts.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 未认证访问受保护资源 UNAUTHORIZED 信封 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiResponseWriter writer;

    public RestAuthenticationEntryPoint(ApiResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writer.writeError(response, ErrorCode.UNAUTHORIZED);
    }
}

