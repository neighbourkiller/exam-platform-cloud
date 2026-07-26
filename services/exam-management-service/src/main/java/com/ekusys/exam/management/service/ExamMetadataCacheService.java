package com.ekusys.exam.management.service;

import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.config.ExamCacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ExamMetadataCacheService {
    private static final Logger log = LoggerFactory.getLogger(ExamMetadataCacheService.class);
    private static final String KEY_PREFIX = "exam:cache:metadata:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ExamCacheProperties properties;
    private final MeterRegistry meterRegistry;

    public ExamMetadataCacheService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                    ExamCacheProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public RuntimeExamMetadata get(Long examId, Supplier<RuntimeExamMetadata> loader) {
        if (!properties.isEnabled()) {
            return loader.get();
        }
        RuntimeExamMetadata cached = read(examId);
        if (cached != null) {
            incrementRequest("hit");
            return cached;
        }
        incrementRequest("miss");
        RuntimeExamMetadata loaded = loader.get();
        incrementRequest("load");
        put(examId, loaded);
        return loaded;
    }

    public void putAfterCommit(RuntimeExamMetadata metadata) {
        afterCommit(() -> put(metadata.examId(), metadata));
    }

    public void evictAfterCommit(Long examId) {
        afterCommit(() -> evict(examId));
    }

    public void put(Long examId, RuntimeExamMetadata metadata) {
        if (!properties.isEnabled() || metadata == null) {
            return;
        }
        try {
            redis.opsForValue().set(
                key(examId),
                objectMapper.writeValueAsString(metadata),
                Duration.ofHours(properties.safeTtlHours())
            );
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("Exam metadata cache write failed, database remains authoritative: examId={}", examId, exception);
            incrementRequest("redis_error");
        }
    }

    public void evict(Long examId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            redis.delete(key(examId));
            meterRegistry.counter("exam.cache.eviction", "cache", "metadata", "outcome", "success").increment();
        } catch (DataAccessException exception) {
            log.warn("Exam metadata cache eviction failed, database state remains authoritative: examId={}",
                examId, exception);
            meterRegistry.counter("exam.cache.eviction", "cache", "metadata", "outcome", "failed").increment();
        }
    }

    private RuntimeExamMetadata read(Long examId) {
        String json;
        try {
            json = redis.opsForValue().get(key(examId));
        } catch (DataAccessException exception) {
            log.warn("Exam metadata cache read failed, fallback to database: examId={}", examId, exception);
            incrementRequest("redis_error");
            return null;
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RuntimeExamMetadata.class);
        } catch (JsonProcessingException exception) {
            log.warn("Invalid exam metadata cache, fallback to database: examId={}", examId, exception);
            incrementRequest("invalid");
            evict(examId);
            return null;
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void incrementRequest(String outcome) {
        meterRegistry.counter("exam.cache.requests", "cache", "metadata", "outcome", outcome).increment();
    }

    private static String key(Long examId) {
        return KEY_PREFIX + examId;
    }
}
