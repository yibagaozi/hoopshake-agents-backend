package com.cnsportiot.cloud.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/** 跨域策略 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** bean 名字必须是 corsConfigurationSource */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration config = new CorsConfiguration();

        // 用 OriginPatterns 而非 setAllowedOrigins:
        // allowCredentials=true 时规范禁止回 "*",而 Patterns 会回显请求的实际 Origin,
        // 既满足规范,又支持 https://*.hoopshake.cn 这类通配
        config.setAllowedOriginPatterns(props.getAllowedOrigins());

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // 请求头全放:前端要带 Authorization、Content-Type,现场设备还要带 X-Service-Token,
        // 逐个列举只会在加新头时又收到一次跨域报错。这里放开的是"请求头名字",不是权限
        config.setAllowedHeaders(List.of("*"));

        if (!props.getExposedHeaders().isEmpty()) {
            config.setExposedHeaders(props.getExposedHeaders());
        }
        config.setAllowCredentials(props.isAllowCredentials());
        config.setMaxAge(props.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        props.getPathPatterns().forEach(pattern -> source.registerCorsConfiguration(pattern, config));

        log.info("CORS 已启用: origins={} paths={} credentials={}",
                props.getAllowedOrigins(), props.getPathPatterns(), props.isAllowCredentials());
        return source;
    }
}

