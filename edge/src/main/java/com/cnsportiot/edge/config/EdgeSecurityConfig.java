package com.cnsportiot.edge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * edge 部署在场边局域网,无用户鉴权体系(教师登录与列课由操作台浏览器直连云端)。
 * 此处全放行,仅用于抵消 Spring Security 在 classpath 上时的默认拦截
 */
@Configuration
public class EdgeSecurityConfig {

    @Bean
    public SecurityFilterChain edgeFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}