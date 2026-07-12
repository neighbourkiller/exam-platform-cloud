package com.ekusys.exam.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@SuppressWarnings("unchecked")
class RefreshTokenSessionServiceTest {

    private StringRedisTemplate redis;
    private RefreshTokenSessionService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        service = new RefreshTokenSessionService(redis);
    }

    @Test
    void rotatesOnlyWhenCurrentTokenMatches() {
        when(redis.execute(
            any(DefaultRedisScript.class),
            eq(List.of("auth:refresh:7")),
            eq("old-token"),
            eq("new-token"),
            anyString()
        )).thenReturn(1L);

        boolean rotated = service.rotate(7L, "old-token", "new-token", Instant.now().plusSeconds(600));

        assertThat(rotated).isTrue();
    }

    @Test
    void rejectsRotationWhenCurrentTokenWasAlreadyConsumed() {
        when(redis.execute(
            any(DefaultRedisScript.class),
            eq(List.of("auth:refresh:7")),
            eq("old-token"),
            eq("new-token"),
            anyString()
        )).thenReturn(0L);

        boolean rotated = service.rotate(7L, "old-token", "new-token", Instant.now().plusSeconds(600));

        assertThat(rotated).isFalse();
    }

    @Test
    void revokesOnlyMatchingToken() {
        when(redis.execute(
            any(DefaultRedisScript.class),
            eq(List.of("auth:refresh:7")),
            eq("current-token")
        )).thenReturn(1L);

        assertThat(service.revoke(7L, "current-token")).isTrue();
    }
}
