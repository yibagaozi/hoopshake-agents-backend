package com.cnsportiot.contracts.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.cnsportiot.contracts.error.FieldError;

import java.util.List;

/**
 * 失败时置于 ApiResponse.data
 * error 为错误码枚举名,前端按此做交互分支;fieldErrors 仅参数校验失败时出现。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorInfo(String error, List<FieldError> fieldErrors) {

    public static ErrorInfo of(String error) {
        return new ErrorInfo(error, null);
    }

    public static ErrorInfo of(String error, List<FieldError> fieldErrors) {
        return new ErrorInfo(error, (fieldErrors == null || fieldErrors.isEmpty()) ? null : fieldErrors);
    }
}

