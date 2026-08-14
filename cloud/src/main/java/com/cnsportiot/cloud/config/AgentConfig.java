package com.cnsportiot.cloud.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用 Agent 系统配置属性。 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {
}
