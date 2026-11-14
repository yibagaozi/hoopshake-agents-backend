package com.cnsportiot.cloud.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 学生账号策略
 *
 * <pre>
 * hoopshake:
 *   student:
 *     initial-password:        # 留空 = 用学号本身作初始密码
 *     activation-code:         # 留空 = 不校验激活码
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.student")
public class StudentProperties {

    /** 初始密码。留空则以学号本身为初始密码 */
    private String initialPassword;

    /**
     * 激活验证码(全局一个,写在本地配置里,教师线下告知学生)。
     * 留空 = 不校验,激活时 verifyCode 可不传——与 {@code register} 的 invite-code 同一套纪律。
     */
    private String activationCode;

    public String initialPasswordFor(String studentNo) {
        return (initialPassword == null || initialPassword.isBlank()) ? studentNo : initialPassword;
    }

    /** 是否启用了激活码校验 */
    public boolean activationCodeRequired() {
        return activationCode != null && !activationCode.isBlank();
    }
}
