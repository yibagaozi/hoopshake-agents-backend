package com.cnsportiot.contracts.error;

import org.springframework.http.HttpStatus;

/** 业务错误码 */
public enum ErrorCode implements ErrorCodeSpec{

    OK(0, HttpStatus.OK, "ok"),

    PARAM_INVALID(40000, HttpStatus.BAD_REQUEST, "参数校验失败"),

    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED, "未登录或凭证缺失"),
    TOKEN_EXPIRED(40101, HttpStatus.UNAUTHORIZED, "登录已过期"),
    TOKEN_INVALID(40102, HttpStatus.UNAUTHORIZED, "登录凭证无效"),
    REFRESH_TOKEN_INVALID(40103, HttpStatus.UNAUTHORIZED, "刷新令牌已失效"),
    BAD_CREDENTIALS(40110, HttpStatus.UNAUTHORIZED, "登录标识或密码错误"),
    SERVICE_TOKEN_INVALID(40120, HttpStatus.UNAUTHORIZED, "服务间凭证无效"),

    FORBIDDEN(40300, HttpStatus.FORBIDDEN, "无权访问"),
    DATA_SCOPE_DENIED(40301, HttpStatus.FORBIDDEN, "越权访问他人数据"),
    ACCOUNT_DISABLED(40310, HttpStatus.FORBIDDEN, "账号已停用"),

    NOT_FOUND(40400, HttpStatus.NOT_FOUND, "资源不存在"),

    CONFLICT(40900, HttpStatus.CONFLICT, "资源冲突"),
    DUPLICATE_IDENTIFIER(40901, HttpStatus.CONFLICT, "登录标识或学号已存在"),
    STATE_CONFLICT(40910, HttpStatus.CONFLICT, "当前状态不允许该操作"),

    RATE_LIMITED(42900, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    TOKEN_BUDGET_EXCEEDED(42910, HttpStatus.TOO_MANY_REQUESTS, "会话 Token 预算已用尽"),

    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误"),
    NOT_IMPLEMENTED(50100, HttpStatus.NOT_IMPLEMENTED, "功能预留,暂未开放"),
    DOWNSTREAM_UNAVAILABLE(50300, HttpStatus.SERVICE_UNAVAILABLE, "依赖服务不可用"),
    LLM_UNAVAILABLE(50310, HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂不可用,请稍后重试");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    @Override public int code() {
        return code;
    }

    @Override public HttpStatus httpStatus() {
        return httpStatus;
    }

    @Override public String defaultMessage() {
        return defaultMessage;
    }

    /** 响应 error 字段即枚举名 */
    @Override public String error() { return name(); }
}
