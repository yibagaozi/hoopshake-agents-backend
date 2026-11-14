package com.cnsportiot.cloud.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 跨域放行清单 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.cloud.cors")
public class CorsProperties {

    /**
     * 允许的来源。走 allowedOriginPatterns 语义,支持 {@code https://*.hoopshake.cn} 这类通配。
     * 缺省只放开本机常见的前端开发端口
     */
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:5173",     // Vite
            "http://127.0.0.1:5173",
            "http://localhost:3000",     // Next / CRA
            "http://127.0.0.1:3000"));

    /** 应用这份策略的路径 */
    private List<String> pathPatterns = new ArrayList<>(List.of("/api/**"));

    /**
     * 是否允许携带凭证(cookie / Authorization)。
     * 置 true 时浏览器要求 Access-Control-Allow-Origin 必须是具体域名而不能是 *,
     * 因此本类一律用 allowedOriginPatterns 让 Spring 回显实际来源
     */
    private boolean allowCredentials = true;

    /**
     * 需要暴露给前端 JS 读取的响应头。
     * 默认为空:traceId 在信封 body 里,前端不需要读任何自定义响应头。
     * 若将来把分页总数之类放进响应头,在这里登记,否则前端读到的是 null
     */
    private List<String> exposedHeaders = new ArrayList<>();

    /** 预检结果缓存秒数,减少 OPTIONS 往返 */
    private long maxAgeSeconds = 3600;
}

