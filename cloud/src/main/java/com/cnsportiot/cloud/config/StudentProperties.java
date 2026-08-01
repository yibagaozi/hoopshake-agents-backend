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

    /**
     * 初始密码。留空则以**学号本身**为初始密码。
     *
     * <p>学号作密码看着很弱,但这里可以接受:账号建出来就是
     * {@code PENDING_ACTIVATION},学生首登被强制走 §2.6 绑手机+改密,
     * 激活后旧密码立即失效、全部 refresh token 被撤销。
     * 真正的风险窗口只有"建档到首登"这一段。
     *
     * <p>若学校要求更严,配一个固定串即可 —— 但那样教师就得逐个告知,
     * 反而更容易走"贴在墙上"的路子。两害相权,默认取学号。
     */
    private String initialPassword;

    public String initialPasswordFor(String studentNo) {
        return (initialPassword == null || initialPassword.isBlank()) ? studentNo : initialPassword;
    }
}
