package com.cnsportiot.cloud.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** 注册 {@code @CurrentUser} 解析器与 {@link RoleInterceptor}。 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final RoleInterceptor roleInterceptor;

    public WebMvcConfig(CurrentUserArgumentResolver currentUserArgumentResolver,
                        RoleInterceptor roleInterceptor) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.roleInterceptor = roleInterceptor;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleInterceptor).addPathPatterns("/api/**");
    }
}
