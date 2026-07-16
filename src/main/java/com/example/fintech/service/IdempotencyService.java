package com.example.fintech.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private static final String REDIS_PREFIX = "idempotency:";
    private static final long LOCK_TTL_HOURS = 2;

    /**
     * Tries to reserve a transaction key.
     * @return true if reserve was successful, false if duplicate detected.
     */
    public boolean reserveKey(String key) {
        String redisKey = REDIS_PREFIX + key;

        Boolean isSet = redisTemplate.opsForValue().setIfAbsent(
                redisKey,
                "PROCESSING",
                LOCK_TTL_HOURS,
                TimeUnit.HOURS
        );

        return Boolean.TRUE.equals(isSet);
    }

    public void updateStatus(String key, String status) {
        redisTemplate.opsForValue().set(REDIS_PREFIX + key, status, LOCK_TTL_HOURS, TimeUnit.HOURS);
    }

    public void removeKey(String key) {
        redisTemplate.delete(REDIS_PREFIX + key);
    }
}