package com.ekusys.exam.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.config.ExamCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExamMetadataCacheServiceTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ObjectMapper objectMapper;
    private ExamMetadataCacheService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new ExamMetadataCacheService(
            redis, objectMapper, new ExamCacheProperties(), new SimpleMeterRegistry()
        );
    }

    @Test
    void returnsCachedMetadata() throws Exception {
        RuntimeExamMetadata expected = metadata();
        when(values.get("exam:cache:metadata:11"))
            .thenReturn(objectMapper.writeValueAsString(expected));

        RuntimeExamMetadata result = service.get(11L, () -> {
            throw new AssertionError("不应回源");
        });

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void redisReadFailureFallsBackToDatabaseAndWriteFailureIsIgnored() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        doThrow(new RedisConnectionFailureException("offline"))
            .when(values).set(
                anyString(), anyString(), org.mockito.ArgumentMatchers.any()
            );

        RuntimeExamMetadata result = service.get(11L, this::metadata);

        assertThat(result).isEqualTo(metadata());
    }

    @Test
    void evictionFailureDoesNotEscape() {
        when(redis.delete("exam:cache:metadata:11"))
            .thenThrow(new RedisConnectionFailureException("offline"));

        service.evict(11L);

        verify(redis).delete("exam:cache:metadata:11");
    }

    @Test
    void evictionRunsOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.evictAfterCommit(11L);
            verify(redis, never()).delete("exam:cache:metadata:11");

            for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(redis).delete("exam:cache:metadata:11");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private RuntimeExamMetadata metadata() {
        return new RuntimeExamMetadata(
            11L, "Java", LocalDateTime.of(2026, 7, 24, 10, 0),
            LocalDateTime.of(2026, 7, 24, 12, 0), 120, 60,
            21L, 1L, 31L, "STANDARD", null
        );
    }
}
