package com.cnsportiot.cloud.exception;

import com.cnsportiot.cloud.common.FieldError;

import java.util.List;

/**
 * 业务异常:承载 {@link ErrorCode} 与可选的自定义提示 / 字段错误
 * application / web 层抛出,由 {@link GlobalExceptionHandler} 统一转成失败信封
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<FieldError> fieldErrors;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        super(message != null ? message : errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    // 便捷工厂

    public static BusinessException of(ErrorCode ec) {
        return new BusinessException(ec);
    }

    public static BusinessException of(ErrorCode ec, String message) {
        return new BusinessException(ec, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    public static BusinessException dataScopeDenied() {
        return new BusinessException(ErrorCode.DATA_SCOPE_DENIED);
    }

    public static BusinessException stateConflict(String message) {
        return new BusinessException(ErrorCode.STATE_CONFLICT, message);
    }

    public static BusinessException notImplemented() {
        return new BusinessException(ErrorCode.NOT_IMPLEMENTED);
    }
}
