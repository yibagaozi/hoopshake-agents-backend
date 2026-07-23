package com.cnsportiot.cloud.security;

import com.cnsportiot.cloud.common.ApiResponse;
import com.cnsportiot.cloud.common.ErrorInfo;
import com.cnsportiot.cloud.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 在过滤器 / EntryPoint 中直接写出统一失败信封 */
@Component
public class ApiResponseWriter {

    private final ObjectMapper objectMapper;

    public ApiResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeError(HttpServletResponse response, ErrorCode ec) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(ec.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<ErrorInfo> body = ApiResponse.error(
                ec.code(), ec.defaultMessage(), ErrorInfo.of(ec.error()));
        objectMapper.writeValue(response.getWriter(), body);
    }
}
