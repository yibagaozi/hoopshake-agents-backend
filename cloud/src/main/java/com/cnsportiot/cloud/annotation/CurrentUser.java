package com.cnsportiot.cloud.annotation;

import java.lang.annotation.*;

/** 注入当前登录用户到 Controller 方法参数 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}

