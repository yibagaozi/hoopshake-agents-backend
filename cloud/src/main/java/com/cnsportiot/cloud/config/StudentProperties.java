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
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.student")
public class StudentProperties {

    /** 初始密码。留空则以学号本身为初始密码 */
    private String initialPassword;

    public String initialPasswordFor(String studentNo) {
        return (initialPassword == null || initialPassword.isBlank()) ? studentNo : initialPassword;
    }
}
