package com.ekusys.exam.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenSessionService {

    private static final String REFRESH_TOKEN_KEY_PREFIX = "auth:refresh:";
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[1])
        if current ~= ARGV[1] then
            return 0
        end
        redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE_IF_MATCHES_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[1])
        if current ~= ARGV[1] then
            return 0
        end
        return redis.call('DEL', KEYS[1])
        """, Long.class);

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

    public boolean rotate(Long userId, String currentTokenId, String newTokenId, Instant expiresAt) {
        if (userId == null || currentTokenId == null || currentTokenId.isBlank()
            || newTokenId == null || newTokenId.isBlank() || expiresAt == null) {
            return false;
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        long ttlMillis = ttl.toMillis();
        if (ttlMillis <= 0) {
            return false;
        }
        Long result = redisTemplate.execute(
            ROTATE_SCRIPT,
            List.of(refreshTokenKey(userId)),
            currentTokenId,
            newTokenId,
            String.valueOf(ttlMillis)
        );
        return result != null && result == 1L;
    }

    public void revoke(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(refreshTokenKey(userId));
    }

    public boolean revoke(Long userId, String tokenId) {
        if (userId == null || tokenId == null || tokenId.isBlank()) {
            return false;
        }
        Long result = redisTemplate.execute(
            REVOKE_IF_MATCHES_SCRIPT,
            List.of(refreshTokenKey(userId)),
            tokenId
        );
        return result != null && result == 1L;
    }

    private String refreshTokenKey(Long userId) {
        return REFRESH_TOKEN_KEY_PREFIX + userId;
    }
}
