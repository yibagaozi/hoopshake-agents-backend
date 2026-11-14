package com.cnsportiot.cloud.annotation;

import java.lang.annotation.*;

/**
 * 注解 需登录
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAuth {
}
