package com.example.fintech.exception;

import java.time.LocalDateTime;
import java.util.List;

/** Consistent error body for every response GlobalExceptionHandler produces. */
public record ApiError(LocalDateTime timestamp, int status, String error, String message, List<String> details) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(LocalDateTime.now(), status, error, message, null);
    }

    public static ApiError of(int status, String error, String message, List<String> details) {
        return new ApiError(LocalDateTime.now(), status, error, message, details);
    }
}
