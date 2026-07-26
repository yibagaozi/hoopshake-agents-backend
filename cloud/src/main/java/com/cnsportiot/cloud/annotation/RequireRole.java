package com.cnsportiot.cloud.annotation;

import com.cnsportiot.cloud.domain.enums.Role;

import java.lang.annotation.*;

/** 注解 指定用户角色 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {
    Role[] value();
}

