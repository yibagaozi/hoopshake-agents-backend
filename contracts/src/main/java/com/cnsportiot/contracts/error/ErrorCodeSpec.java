package com.cnsportiot.contracts.error;

import org.springframework.http.HttpStatus;

public interface ErrorCodeSpec {
    int code();
    HttpStatus httpStatus();
    String defaultMessage();
    String error();
}
