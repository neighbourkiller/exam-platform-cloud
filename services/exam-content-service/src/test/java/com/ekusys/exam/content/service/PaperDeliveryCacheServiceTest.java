package com.ekusys.exam.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.content.api.PaperSnapshotQuestion;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.content.config.ExamCacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PaperDeliveryCacheServiceTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ObjectMapper objectMapper;
    private PaperDeliveryCacheService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        objectMapper = new ObjectMapper();
        ExamCacheProperties properties = new ExamCacheProperties();
        service = new PaperDeliveryCacheService(
            redis, objectMapper, properties, new SimpleMeterRegistry()
        );
    }

    @Test
    void returnsCachedDeliveryWithoutLoadingDatabase() throws Exception {
        PaperSnapshotView delivery = snapshot(null, null);
        when(values.get("exam:cache:paper-delivery:11"))
            .thenReturn(objectMapper.writeValueAsString(delivery));
        @SuppressWarnings("unchecked")
        Supplier<PaperSnapshotView> loader = mock(Supplier.class);

        PaperSnapshotView result = service.get(11L, loader);

        assertThat(result).isEqualTo(delivery);
        verify(loader, never()).get();
    }

    @Test
    void cacheMissLoadsOnceAndStoresDeliveryView() throws Exception {
        when(values.get("exam:cache:paper-delivery:11")).thenReturn(null);
        when(values.setIfAbsent(
            eq("exam:cache:lock:paper-delivery:11"), anyString(), any(Duration.class)
        )).thenReturn(true);
        PaperSnapshotView grading = snapshot("A", "解析");

        PaperSnapshotView result = service.get(11L, () -> grading);

        assertThat(result).isEqualTo(grading);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(
            eq("exam:cache:paper-delivery:11"),
            json.capture(),
            eq(Duration.ofHours(48))
        );
        PaperSnapshotView cached = objectMapper.readValue(json.getValue(), PaperSnapshotView.class);
        assertThat(cached.questions().getFirst().answer()).isNull();
        assertThat(cached.questions().getFirst().analysis()).isNull();
    }

    @Test
    void lockLoserWaitsForWinnerCacheInsteadOfLoadingDatabase() throws Exception {
        PaperSnapshotView delivery = snapshot(null, null);
        when(values.get("exam:cache:paper-delivery:11"))
            .thenReturn(null, objectMapper.writeValueAsString(delivery));
        when(values.setIfAbsent(
            eq("exam:cache:lock:paper-delivery:11"), anyString(), any(Duration.class)
        )).thenReturn(false);
        @SuppressWarnings("unchecked")
        Supplier<PaperSnapshotView> loader = mock(Supplier.class);

        PaperSnapshotView result = service.get(11L, loader);

        assertThat(result).isEqualTo(delivery);
        verify(loader, never()).get();
    }

    @Test
    void redisFailureFallsBackToLoader() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new RedisConnectionFailureException("offline"));
        PaperSnapshotView expected = snapshot(null, null);

        PaperSnapshotView result = service.get(11L, () -> expected);

        assertThat(result).isEqualTo(expected);
    }

    private PaperSnapshotView snapshot(String answer, String analysis) {
        return new PaperSnapshotView(
            11L, 21L, 1L, "Java", 31L, 100,
            List.of(new PaperSnapshotQuestion(
                41L, "SINGLE", "EASY", "题目", "[\"A\"]",
                answer, analysis, 10, 1
            ))
        );
    }
}
