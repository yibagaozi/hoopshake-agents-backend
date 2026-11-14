package com.cnsportiot.contracts.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.OffsetDateTime;

/**
 * 统一响应信封
 * 成功:code=0、message="ok"、data 为业务数据;失败:data 为 {@link ErrorInfo}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId,
        OffsetDateTime timestamp) {

    /** 链路 ID 由日志/追踪过滤器写入 MDC("traceId"),此处读取 */
    private static String currentTraceId() {
        String t = MDC.get("traceId");
        return (t == null || t.isBlank()) ? null : t;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, currentTraceId(), OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    /** 失败信封(由 GlobalExceptionHandler 使用);code 与 message 取自 ErrorCode */
    public static ApiResponse<ErrorInfo> error(int code, String message, ErrorInfo info) {
        return new ApiResponse<>(code, message, info, currentTraceId(), OffsetDateTime.now());
    }
}

