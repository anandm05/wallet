package com.example.fintech.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor idempotencyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Intercept only the ledger API endpoints
        registry.addInterceptor(idempotencyInterceptor)
                .addPathPatterns("/api/v1/ledger/**");
    }
}