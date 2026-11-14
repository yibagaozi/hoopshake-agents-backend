package com.cnsportiot.cloud.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** JWT 与服务间鉴权配置 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.jwt")
public class JwtProperties {

    /** HMAC 密钥,≥32 字节;生产由环境变量注入。 */
    private String secret;

    private String issuer = "hoopshake-cloud";

    private Duration accessTtl = Duration.ofMinutes(30);

    private Duration refreshTtl = Duration.ofDays(14);
}
