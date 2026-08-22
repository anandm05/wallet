package com.example.fintech.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
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
                .addPathPatterns("/api/v1/transfers/**");
    }

    // Boot 4.1's JacksonAutoConfiguration wires up the new Jackson 3
    // tools.jackson.databind.json.JsonMapper, not the classic
    // com.fasterxml.jackson.databind.ObjectMapper this codebase autowires
    // throughout (TransferService, OutboxPublisher, BalanceProjectionConsumer,
    // JsonNodeConverter) - no starter auto-provides that bean type anymore.
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
