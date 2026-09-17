package com.cnsportiot.cloud.security;

import com.cnsportiot.cloud.config.RegisterProperties;
import com.cnsportiot.cloud.config.StudentProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, RegisterProperties.class, StudentProperties.class})
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ServiceTokenFilter serviceTokenFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          ServiceTokenFilter serviceTokenFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.serviceTokenFilter = serviceTokenFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {}) // 跨域策略按网关/前端域名单独配置 CorsConfigurationSource
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers(
                    "/api/auth/login",
                    "/api/auth/refresh",
                    "/api/auth/register",
                    "/api/auth/activate").permitAll()
                .requestMatchers("/api/parent/**").permitAll()   // 由 Controller 统一返回 501
                // 探针与错误页保持公开(容器 liveness/readiness 要用)
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
                // 指标含运行细节(业务量、连接池、各路径耗时),不再公开:
                // Prometheus 带 X-Service-Token 抓取(ROLE_SERVICE),管理员用 JWT 查看(ROLE_ADMIN)
                .requestMatchers("/actuator/prometheus", "/actuator/metrics/**")
                    .hasAnyRole("ADMIN", "SERVICE")
                .requestMatchers("/api/ingest/**").permitAll()   // 由 ServiceTokenFilter 校验
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            // ingest 服务间校验在前,再是用户 JWT
            .addFilterBefore(serviceTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(jwtAuthFilter, ServiceTokenFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

