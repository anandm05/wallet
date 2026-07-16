package com.example.fintech.config;

import com.example.fintech.service.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final IdempotencyService idempotencyService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Only enforce this for POST operations (like creating a ledger entry)
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String key = request.getHeader("X-Idempotency-Key");

            if (key == null || key.trim().isEmpty()) {
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                response.getWriter().write("Missing required 'X-Idempotency-Key' header.");
                return false;
            }

            // Attempt to claim the key in Redis (SETNX wrapper)
            boolean isUnique = idempotencyService.reserveKey(key);
            if (!isUnique) {
                response.setStatus(HttpStatus.CONFLICT.value());
                response.getWriter().write("Duplicate transaction detected. Processing or completed.");
                return false;
            }
        }
        return true;
    }
}