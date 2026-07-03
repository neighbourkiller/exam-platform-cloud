package com.ekusys.exam.auth.service;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenSessionService {

    private static final String REFRESH_TOKEN_KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redisTemplate;

    public RefreshTokenSessionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void store(Long userId, String tokenId, Instant expiresAt) {
        if (userId == null || tokenId == null || tokenId.isBlank() || expiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(refreshTokenKey(userId), tokenId, ttl);
    }

    public boolean isActive(Long userId, String tokenId) {
        if (userId == null || tokenId == null || tokenId.isBlank()) {
            return false;
        }
        String storedTokenId = redisTemplate.opsForValue().get(refreshTokenKey(userId));
        return tokenId.equals(storedTokenId);
    }

    public void revoke(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(refreshTokenKey(userId));
    }

    private String refreshTokenKey(Long userId) {
        return REFRESH_TOKEN_KEY_PREFIX + userId;
    }
}
