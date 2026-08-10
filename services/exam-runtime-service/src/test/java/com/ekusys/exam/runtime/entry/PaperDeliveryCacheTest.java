package com.ekusys.exam.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.content.api.PaperSnapshotQuestion;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.runtime.client.ContentRuntimeClient;
import com.ekusys.exam.runtime.config.ExamEntryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PaperDeliveryCacheTest {
    @Test
    void coldLocalCacheUsesOneContentLoadForConcurrentRequests() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        ContentRuntimeClient content = mock(ContentRuntimeClient.class);
        PaperSnapshotView paper = paper();
        when(content.delivery(21L)).thenReturn(ApiResponse.ok(paper));
        PaperDeliveryCache cache = new PaperDeliveryCache(
            redis, content, new ObjectMapper(), new ExamEntryProperties(), new SimpleMeterRegistry()
        );
        var executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PaperSnapshotView>> futures = java.util.stream.IntStream.range(0, 50)
                .mapToObj(index -> executor.submit(() -> {
                    start.await();
                    return cache.get(21L);
                }))
                .toList();
            start.countDown();

            for (Future<PaperSnapshotView> future : futures) {
                assertThat(future.get().snapshotId()).isEqualTo(21L);
            }
            verify(content, times(1)).delivery(21L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void l2HitDoesNotCallContent() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper mapper = new ObjectMapper();
        when(values.get("exam:paper-delivery:21")).thenReturn(mapper.writeValueAsString(paper()));
        ContentRuntimeClient content = mock(ContentRuntimeClient.class);
        PaperDeliveryCache cache = new PaperDeliveryCache(
            redis, content, mapper, new ExamEntryProperties(), new SimpleMeterRegistry()
        );

        assertThat(cache.get(21L).snapshotId()).isEqualTo(21L);
        verify(content, never()).delivery(any());
    }

    @Test
    void prewarmRestoresMissingL2FromLocalImmutableSnapshot() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        ContentRuntimeClient content = mock(ContentRuntimeClient.class);
        when(content.delivery(21L)).thenReturn(ApiResponse.ok(paper()));
        PaperDeliveryCache cache = new PaperDeliveryCache(
            redis, content, new ObjectMapper(), new ExamEntryProperties(), new SimpleMeterRegistry()
        );

        cache.get(21L);
        cache.prewarm(21L);

        verify(content, times(1)).delivery(21L);
        verify(values, times(2)).set(
            org.mockito.ArgumentMatchers.eq("exam:paper-delivery:21"),
            anyString(), any(Duration.class)
        );
    }

    private PaperSnapshotView paper() {
        return new PaperSnapshotView(
            21L, 31L, 1L, "Java 试卷", 41L, 100,
            List.of(new PaperSnapshotQuestion(
                51L, "SINGLE", "MEDIUM", "1+1=?", "[\"A\",\"B\"]",
                null, null, 5, 1, List.of()
            ))
        );
    }
}
