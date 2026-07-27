package com.cnsportiot.edge.exception;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.ErrorInfo;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.contracts.error.ErrorCodeSpec;
import com.cnsportiot.contracts.error.FieldError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常:按携带的 ErrorCodeSpec 输出 */
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

    /** @Valid 请求体校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ErrorInfo>> handleMethodArgNotValid(MethodArgumentNotValidException ex) {
        return paramInvalid(ex.getBindingResult());
    }

    /** 表单/查询参数绑定失败 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<ErrorInfo>> handleBind(BindException ex) {
        return paramInvalid(ex.getBindingResult());
    }

    /** 处理方法参数级校验失败(Spring 6.1+ 取代 ConstraintViolationException) */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<ErrorInfo>> handleMethodValidation(HandlerMethodValidationException ex) {
        List<FieldError> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(err -> new FieldError(
                                paramName(result.getMethodParameter()),
                                err.getDefaultMessage())))
                .toList();
        return build(ErrorCode.PARAM_INVALID, null, fieldErrors);
    }

    /** 兜底 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorInfo>> handleOthers(Exception ex) {
        log.error("未捕获异常", ex);
        return build(ErrorCode.INTERNAL_ERROR, null, null);
    }

    private ResponseEntity<ApiResponse<ErrorInfo>> paramInvalid(BindingResult br) {
        List<FieldError> fieldErrors = br.getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(ErrorCode.PARAM_INVALID, null, fieldErrors);
    }

    private ResponseEntity<ApiResponse<ErrorInfo>> build(
            ErrorCodeSpec ec, String message, List<FieldError> fieldErrors) {
        ErrorInfo info = new ErrorInfo(ec.error(), fieldErrors);
        ApiResponse<ErrorInfo> body = ApiResponse.error(
                ec.code(), message != null ? message : ec.defaultMessage(), info);
        return ResponseEntity.status(ec.httpStatus()).body(body);
    }

    private static String paramName(MethodParameter p) {
        String name = p.getParameterName();
        return name != null ? name : "arg" + p.getParameterIndex();
    }
}

