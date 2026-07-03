package com.ekusys.exam.common.security;

import com.ekusys.exam.auth.config.AuthRateLimitProperties;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class AuthRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitService.class);

    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
        """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window)
            return 1
            """,
        Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final AuthRateLimitProperties properties;

    public AuthRateLimitService(StringRedisTemplate redisTemplate, AuthRateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean isAllowed(String action, String clientIp, long limit) {
        long now = System.currentTimeMillis();
        long windowMillis = properties.getWindowSeconds() * 1000;
        String key = properties.getKeyPrefix() + action + ":" + clientIp;
        String member = now + "-" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        try {
            Long result = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowMillis),
                String.valueOf(limit),
                member
            );
            return result == null || result == 1L;
        } catch (Exception ex) {
            log.warn("Auth rate limiter unavailable, fail open. action={}, ip={}", action, clientIp, ex);
            return true;
        }
    }
}
