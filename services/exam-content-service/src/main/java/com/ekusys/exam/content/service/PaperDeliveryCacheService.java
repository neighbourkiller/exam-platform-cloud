package com.ekusys.exam.content.service;

import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.content.config.ExamCacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
public class PaperDeliveryCacheService {
    private static final Logger log = LoggerFactory.getLogger(PaperDeliveryCacheService.class);
    private static final String KEY_PREFIX = "exam:cache:paper-delivery:";
    private static final String LOCK_PREFIX = "exam:cache:lock:paper-delivery:";
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
        Long.class
    );

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ExamCacheProperties properties;
    private final MeterRegistry meterRegistry;

    public PaperDeliveryCacheService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                     ExamCacheProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public PaperSnapshotView get(Long snapshotId, Supplier<PaperSnapshotView> loader) {
        if (!properties.isEnabled()) {
            return loader.get();
        }
        PaperSnapshotView cached = read(snapshotId);
        if (cached != null) {
            incrementRequest("hit");
            return cached;
        }
        incrementRequest("miss");
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(
                lockKey(snapshotId), lockToken, Duration.ofMillis(properties.safeLockTtlMillis())
            );
        } catch (DataAccessException exception) {
            redisError("acquire", snapshotId, exception);
            return loadDirect(loader);
        }
        if (Boolean.TRUE.equals(acquired)) {
            incrementLock("acquired");
            try {
                PaperSnapshotView loaded = loadDirect(loader);
                put(snapshotId, loaded);
                return loaded;
            } finally {
                release(snapshotId, lockToken);
            }
        }
        incrementLock("waited");
        PaperSnapshotView waited = waitForCache(snapshotId);
        if (waited != null) {
            return waited;
        }
        incrementLock("timeout");
        return loadDirect(loader);
    }

    public void put(Long snapshotId, PaperSnapshotView value) {
        if (!properties.isEnabled() || value == null) {
            return;
        }
        try {
            redis.opsForValue().set(
                cacheKey(snapshotId),
                objectMapper.writeValueAsString(value.deliveryView()),
                Duration.ofHours(properties.safeTtlHours())
            );
        } catch (DataAccessException | JsonProcessingException exception) {
            redisError("write", snapshotId, exception);
        }
    }

    private PaperSnapshotView read(Long snapshotId) {
        String json;
        try {
            json = redis.opsForValue().get(cacheKey(snapshotId));
        } catch (DataAccessException exception) {
            redisError("read", snapshotId, exception);
            return null;
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PaperSnapshotView.class);
        } catch (JsonProcessingException exception) {
            log.warn("Invalid paper delivery cache, fallback to Content DB: snapshotId={}", snapshotId, exception);
            incrementRequest("invalid");
            deleteQuietly(snapshotId);
            return null;
        }
    }

    private PaperSnapshotView waitForCache(Long snapshotId) {
        long waitMillis = properties.safeLockWaitMillis();
        if (waitMillis == 0) {
            return null;
        }
        long deadline = System.nanoTime() + Duration.ofMillis(waitMillis).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(properties.safeLockPollMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
            PaperSnapshotView cached = read(snapshotId);
            if (cached != null) {
                incrementRequest("hit_after_wait");
                return cached;
            }
        }
        return null;
    }

    private PaperSnapshotView loadDirect(Supplier<PaperSnapshotView> loader) {
        incrementRequest("load");
        return loader.get();
    }

    private void release(Long snapshotId, String token) {
        try {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey(snapshotId)), token);
        } catch (DataAccessException exception) {
            redisError("unlock", snapshotId, exception);
        }
    }

    private void deleteQuietly(Long snapshotId) {
        try {
            redis.delete(cacheKey(snapshotId));
        } catch (DataAccessException exception) {
            redisError("delete_invalid", snapshotId, exception);
        }
    }

    private void redisError(String operation, Long snapshotId, Exception exception) {
        log.warn("Paper delivery cache operation failed, fallback allowed: operation={}, snapshotId={}",
            operation, snapshotId, exception);
        incrementRequest("redis_error");
    }

    private void incrementRequest(String outcome) {
        meterRegistry.counter("exam.cache.requests", "cache", "paper", "outcome", outcome).increment();
    }

    private void incrementLock(String outcome) {
        meterRegistry.counter("exam.cache.lock", "cache", "paper", "outcome", outcome).increment();
    }

    private static String cacheKey(Long snapshotId) {
        return KEY_PREFIX + snapshotId;
    }

    private static String lockKey(Long snapshotId) {
        return LOCK_PREFIX + snapshotId;
    }
}
