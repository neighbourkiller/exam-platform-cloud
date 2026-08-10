package com.ekusys.exam.runtime.entry;

import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.runtime.client.ContentRuntimeClient;
import com.ekusys.exam.runtime.config.ExamEntryProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaperDeliveryCache {
    private static final Logger log = LoggerFactory.getLogger(PaperDeliveryCache.class);
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    private final StringRedisTemplate redis;
    private final ContentRuntimeClient content;
    private final ObjectMapper mapper;
    private final ExamEntryProperties properties;
    private final Cache<Long, CachedPaper> l1;
    private final Counter l1Hits;
    private final Counter l2Hits;
    private final Counter misses;
    private final Timer loadTimer;
    private final DistributionSummary payloadSize;

    public PaperDeliveryCache(StringRedisTemplate redis, ContentRuntimeClient content,
                              ObjectMapper mapper, ExamEntryProperties properties,
                              MeterRegistry registry) {
        this.redis = redis;
        this.content = content;
        this.mapper = mapper;
        this.properties = properties;
        this.l1 = Caffeine.<Long, CachedPaper>newBuilder()
            .maximumWeight(properties.safeL1MaximumBytes())
            .weigher((Long key, CachedPaper value) ->
                Math.max(1, Math.min(Integer.MAX_VALUE, value.bytes())))
            .expireAfterAccess(properties.safeL1ExpireAfterAccessMinutes(), TimeUnit.MINUTES)
            .recordStats()
            .build();
        this.l1Hits = registry.counter("exam.paper.cache.hit", "level", "L1");
        this.l2Hits = registry.counter("exam.paper.cache.hit", "level", "L2");
        this.misses = registry.counter("exam.paper.cache.miss");
        this.loadTimer = Timer.builder("exam.paper.cache.load.duration")
            .publishPercentileHistogram().register(registry);
        this.payloadSize = DistributionSummary.builder("exam.paper.delivery.payload.bytes")
            .baseUnit("bytes").register(registry);
    }

    public PaperSnapshotView get(Long snapshotId) {
        if (snapshotId == null) {
            throw unavailable("考试试卷尚未准备完成");
        }
        CachedPaper local = l1.getIfPresent(snapshotId);
        if (local != null) {
            l1Hits.increment();
            payloadSize.record(local.bytes());
            return local.paper();
        }
        try {
            CachedPaper loaded = l1.get(snapshotId, this::load);
            if (loaded == null) {
                throw unavailable("考试试卷加载失败");
            }
            payloadSize.record(loaded.bytes());
            return loaded.paper();
        } catch (ExamEntryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Paper delivery cache load failed: snapshotId={}", snapshotId, exception);
            throw unavailable("考试试卷暂时不可用，请稍后重试");
        }
    }

    public void prewarm(Long snapshotId) {
        PaperSnapshotView paper = get(snapshotId);
        try {
            String key = cacheKey(snapshotId);
            String cached = redis.opsForValue().get(key);
            if (cached == null || cached.isBlank()) {
                redis.opsForValue().set(
                    key,
                    mapper.writeValueAsString(paper.deliveryView()),
                    Duration.ofHours(properties.safeL2TtlHours())
                );
            }
        } catch (DataAccessException exception) {
            throw unavailable("考试缓存暂时不可用，请稍后重试");
        } catch (JsonProcessingException exception) {
            throw unavailable("试卷缓存序列化失败");
        }
    }

    private CachedPaper load(Long snapshotId) {
        Timer.Sample sample = Timer.start();
        try {
            String cached = redis.opsForValue().get(cacheKey(snapshotId));
            if (cached != null && !cached.isBlank()) {
                l2Hits.increment();
                return decode(cached);
            }
            misses.increment();
            return loadSingleFlight(snapshotId);
        } catch (DataAccessException exception) {
            throw unavailable("考试缓存暂时不可用，请稍后重试");
        } finally {
            sample.stop(loadTimer);
        }
    }

    private CachedPaper loadSingleFlight(Long snapshotId) {
        String lockKey = lockKey(snapshotId);
        String owner = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, owner, Duration.ofSeconds(30));
        if (Boolean.TRUE.equals(acquired)) {
            try {
                PaperSnapshotView response = content.delivery(snapshotId).getData();
                if (response == null || !snapshotId.equals(response.snapshotId())) {
                    throw unavailable("试卷服务返回了无效快照");
                }
                PaperSnapshotView paper = response.deliveryView();
                String json = mapper.writeValueAsString(paper);
                redis.opsForValue().set(
                    cacheKey(snapshotId), json, Duration.ofHours(properties.safeL2TtlHours())
                );
                return new CachedPaper(paper, json.getBytes(StandardCharsets.UTF_8).length);
            } catch (JsonProcessingException exception) {
                throw unavailable("试卷缓存序列化失败");
            } finally {
                try {
                    redis.execute(RELEASE_LOCK, List.of(lockKey), owner);
                } catch (DataAccessException exception) {
                    log.debug("Failed to release paper cache lock: snapshotId={}", snapshotId, exception);
                }
            }
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.safeCacheLoadWaitMs());
        while (System.nanoTime() < deadline) {
            String cached = redis.opsForValue().get(cacheKey(snapshotId));
            if (cached != null && !cached.isBlank()) {
                l2Hits.increment();
                return decode(cached);
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw unavailable("等待试卷缓存时请求被中断");
            }
        }
        throw unavailable("考试试卷仍在加载，请稍后重试");
    }

    private CachedPaper decode(String json) {
        try {
            return new CachedPaper(
                mapper.readValue(json, PaperSnapshotView.class),
                json.getBytes(StandardCharsets.UTF_8).length
            );
        } catch (JsonProcessingException exception) {
            throw unavailable("考试试卷缓存内容无效");
        }
    }

    private ExamEntryException unavailable(String message) {
        return new ExamEntryException(
            HttpStatus.SERVICE_UNAVAILABLE, "EXAM_PAPER_UNAVAILABLE", message, 500L
        );
    }

    private String cacheKey(Long snapshotId) {
        return "exam:paper-delivery:" + snapshotId;
    }

    private String lockKey(Long snapshotId) {
        return "exam:paper-delivery-lock:" + snapshotId;
    }

    private record CachedPaper(PaperSnapshotView paper, int bytes) {
    }
}
