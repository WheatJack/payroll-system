package com.insbean.payroll.config;

import com.insbean.payroll.security.PermissionInterceptor;
import com.insbean.payroll.security.TokenService;
import com.insbean.payroll.security.mapper.AuthMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    public WebConfig(TokenService tokenService, AuthMapper authMapper) {
        this.permissionInterceptor = new PermissionInterceptor(tokenService, authMapper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/**");
    }
}
