package com.cnsportiot.contracts.error;

import java.util.List;

/**
 * 业务异常:承载 {@link ErrorCode} 与可选的自定义提示 / 字段错误
 * application / web 层抛出,由 GlobalExceptionHandler 统一转成失败信封
 */
public class BusinessException extends RuntimeException {

    private final ErrorCodeSpec errorCode;
    private final transient List<FieldError> fieldErrors;

    public BusinessException(ErrorCodeSpec errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BusinessException(ErrorCodeSpec errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCodeSpec errorCode, String message, List<FieldError> fieldErrors) {
        super(message != null ? message : errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors;
    }

    public ErrorCodeSpec errorCode() {
        return errorCode;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    // 便捷工厂

    public static BusinessException of(ErrorCodeSpec ec) {
        return new BusinessException(ec);
    }

    public static BusinessException of(ErrorCodeSpec ec, String message) {
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
