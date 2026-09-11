package com.cnsportiot.edge.rules;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用实时规则引擎配置绑定。 */
@Configuration
@EnableConfigurationProperties(CheckpointProperties.class)
public class RulesConfig {
}
