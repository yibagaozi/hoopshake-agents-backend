package com.cnsportiot.cloud.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.register")
public class RegisterProperties {

    /** 是否开放教师自助注册;false 时接口返回 50100 NOT_IMPLEMENTED */
    private boolean enabled = false;

    /** 邀请码;为空表示不校验 */
    private String inviteCode;
}

