package com.cnsportiot.contracts.error;

/** 单字段校验错误 */
public record FieldError(String field, String reason) {

}
