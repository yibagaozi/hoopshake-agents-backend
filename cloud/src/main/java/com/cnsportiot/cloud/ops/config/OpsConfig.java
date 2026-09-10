package com.cnsportiot.cloud.ops.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 运维包配置装配 */
@Configuration
@EnableConfigurationProperties(OpsProperties.class)
public class OpsConfig {
}
