package com.cnsportiot.cloud.exception;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.ErrorInfo;
import com.cnsportiot.contracts.error.ErrorCodeSpec;
import com.cnsportiot.contracts.error.FieldError;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 全局异常处理器
 * 统一将异常转换为失败信封,HTTP 状态码与 body.code 一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常:按携带的 ErrorCode 输出 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<ErrorInfo>> handleBusiness(BusinessException ex) {
        ErrorCodeSpec ec = ex.errorCode();
        if (ec.httpStatus().is5xxServerError()) {
            log.error("业务异常({}): {}", ec.error(), ex.getMessage(), ex);
        } else {
            log.debug("业务异常({}): {}", ec.error(), ex.getMessage());
        }
        return build(ec, ex.getMessage(), ex.fieldErrors());
    }

    /** @Valid 请求体 / 表单校验失败 PARAM_INVALID + fieldErrors */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<ErrorInfo>> handleBind(BindException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.defaultMessage(), fieldErrors);
    }

    /** 路径/查询参数上的约束(@Validated 方法级)校验失败 PARAM_INVALID */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<ErrorInfo>> handleConstraint(ConstraintViolationException ex) {
        List<FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(lastNode(v.getPropertyPath()), v.getMessage()))
                .toList();
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.defaultMessage(), fieldErrors);
    }

    /** 缺参 / 类型不匹配 / 请求体不可读 PARAM_INVALID */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<ErrorInfo>> handleBadRequest(Exception ex) {
        log.debug("请求参数异常: {}", ex.getMessage());
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.defaultMessage(), null);
    }

    /** 已认证但无权限(方法安全 / @RequireRole 抛出) FORBIDDEN */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<ErrorInfo>> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), null);
    }

    /** 方法不支持 复用 NOT_FOUND */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<ErrorInfo>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.NOT_FOUND, "请求方法不支持", null);
    }

    /** 未匹配到路由 / 静态资源 NOT_FOUND */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<ErrorInfo>> handleNotFound(Exception ex) {
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage(), null);
    }

    /** 未预期异常 INTERNAL_ERROR */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorInfo>> handleOther(Exception ex) {
        log.error("未捕获异常", ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null);
    }

    // helpers

    private ResponseEntity<ApiResponse<ErrorInfo>> build(ErrorCodeSpec ec, String message, List<FieldError> fieldErrors) {
        ErrorInfo info = ErrorInfo.of(ec.error(), fieldErrors);
        ApiResponse<ErrorInfo> body = ApiResponse.error(
                ec.code(), message != null ? message : ec.defaultMessage(), info);
        return ResponseEntity.status(ec.httpStatus()).body(body);
    }

    private static String lastNode(Path path) {
        String name = null;
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name;
    }
}

