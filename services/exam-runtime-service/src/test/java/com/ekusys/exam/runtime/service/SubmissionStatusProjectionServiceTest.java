package com.ekusys.exam.runtime.service;

import com.ekusys.exam.exam.dto.SubmissionStatusView;
import com.ekusys.exam.runtime.messaging.SubmissionAcceptedReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionStatusProjectionServiceTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOperations;
    private TimeoutSubmissionMetrics metrics;
    private SubmissionStatusProjectionService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        metrics = mock(TimeoutSubmissionMetrics.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new SubmissionStatusProjectionService(redis, objectMapper, metrics, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cachedProjectionHitReturnsSubmittedStatusWithMatchingReceiptTimestamps() throws Exception {
        LocalDateTime submittedAt = LocalDateTime.of(2026, 9, 3, 10, 0, 1, 123_000_000);
        LocalDateTime finalizedAt = LocalDateTime.of(2026, 9, 3, 10, 0, 1, 456_000_000);
        SubmissionStatusProjectionService.FinalizedStatusProjection projection =
            new SubmissionStatusProjectionService.FinalizedStatusProjection(
                100L, "SUBMITTED", "PROCESSING", true, "DONE", 5L,
                submittedAt, finalizedAt
            );
        when(valueOperations.get("exam:submission-status:10:20"))
            .thenReturn(objectMapper.writeValueAsString(projection));

        SubmissionStatusView view = service.getCachedProjection(10L, 20L);

        assertThat(view).isNotNull();
        assertThat(view.getSubmissionId()).isEqualTo(100L);
        assertThat(view.getSessionStatus()).isEqualTo("SUBMITTED");
        assertThat(view.getSubmittedAt()).isEqualTo(submittedAt);
        assertThat(view.getFinalizedAt()).isEqualTo(finalizedAt);
        assertThat(view.getPhase()).isEqualTo("RUNTIME_FINALIZED");
        assertThat(view.getRuntimeFinalized()).isTrue();
        assertThat(view.getServerEpochMs()).isPositive();
        verify(metrics).recordProjection("hit");
    }

    @Test
    void cacheMissReturnsNullAndRecordsMissMetric() {
        when(valueOperations.get("exam:submission-status:10:20")).thenReturn(null);

        SubmissionStatusView view = service.getCachedProjection(10L, 20L);

        assertThat(view).isNull();
        verify(metrics).recordProjection("miss");
    }

    @Test
    void inflightProjectionUsesThirtySecondTtlAndRefreshesResponseClock() throws Exception {
        SubmissionStatusView stored = SubmissionStatusView.builder()
            .sessionStatus("AUTO_SUBMITTING")
            .phase("SUBMITTING")
            .runtimeFinalized(false)
            .serverEpochMs(1L)
            .build();
        when(valueOperations.get("exam:submission-status:inflight:10:20"))
            .thenReturn(objectMapper.writeValueAsString(stored));

        service.recordInflightProjection(10L, 20L, stored);
        SubmissionStatusView cached = service.getInflightProjection(10L, 20L);

        verify(valueOperations).set(
            eq("exam:submission-status:inflight:10:20"), anyString(), eq(Duration.ofSeconds(30))
        );
        assertThat(cached.getServerEpochMs()).isGreaterThan(1L);
        assertThat(cached.getPhase()).isEqualTo("SUBMITTING");
    }

    @Test
    void recordSubmittedAfterCommitExecutesViaSetIfAbsent() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            LocalDateTime submittedAt = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
            LocalDateTime finalizedAt = LocalDateTime.of(2026, 9, 3, 10, 0, 1);
            SubmissionAcceptedReceipt receipt = new SubmissionAcceptedReceipt(
                100L, 10L, 20L, submittedAt, finalizedAt, 1L, true
            );
            service.recordSubmittedAfterCommit(receipt);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCommit()
            );
            verify(valueOperations).setIfAbsent(eq("exam:submission-status:10:20"), anyString(), any());
            verify(metrics).recordProjection("written");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackTransactionDoesNotProduceCache() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            SubmissionAcceptedReceipt receipt = new SubmissionAcceptedReceipt(
                100L, 10L, 20L, LocalDateTime.now(), LocalDateTime.now(), 1L, true
            );
            service.recordSubmittedAfterCommit(receipt);
            // Simulate rollback: do NOT invoke afterCommit()
            verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void duplicateSubmissionDoesNotIncrementWrittenIfAlreadyPresent() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        SubmissionAcceptedReceipt receipt = new SubmissionAcceptedReceipt(
            100L, 10L, 20L, LocalDateTime.now(), LocalDateTime.now(), 1L, true
        );
        service.recordSubmittedAfterCommit(receipt);
        verify(valueOperations).setIfAbsent(eq("exam:submission-status:10:20"), anyString(), any());
        verify(metrics, never()).recordProjection("written");
    }

    @Test
    void incompleteReceiptDoesNotWriteFabricatedTimestamps() {
        SubmissionAcceptedReceipt receipt = new SubmissionAcceptedReceipt(
            100L, 10L, 20L, null, null, 1L, true
        );

        service.recordSubmittedAfterCommit(receipt);

        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any());
        verify(metrics).recordProjection("invalid_receipt");
    }

    @Test
    void redisBlockedThreeSecondsDoesNotBlockCallingThread() throws Exception {
        Executor asyncExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch redisBlockLatch = new CountDownLatch(1);
        AtomicBoolean threadFinished = new AtomicBoolean(false);

        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenAnswer(invocation -> {
            redisBlockLatch.await(3, TimeUnit.SECONDS);
            return true;
        });

        SubmissionStatusProjectionService asyncService = new SubmissionStatusProjectionService(
            redis, objectMapper, metrics, asyncExecutor
        );

        long start = System.currentTimeMillis();
        asyncService.recordSubmittedAfterCommit(new SubmissionAcceptedReceipt(
            100L, 10L, 20L, LocalDateTime.now(), LocalDateTime.now(), 1L, true
        ));
        long elapsed = System.currentTimeMillis() - start;

        // Calling thread must return immediately without waiting for the 3s redis block
        assertThat(elapsed).isLessThan(500);

        redisBlockLatch.countDown();
    }

    @Test
    void queueSaturationDropsGracefullyWithoutThrowingException() {
        Executor rejectingExecutor = command -> {
            throw new RuntimeException("Queue full");
        };
        SubmissionStatusProjectionService rejectingService = new SubmissionStatusProjectionService(
            redis, objectMapper, metrics, rejectingExecutor
        );

        // Must not throw exception to caller
        rejectingService.recordSubmittedAfterCommit(new SubmissionAcceptedReceipt(
            100L, 10L, 20L, LocalDateTime.now(), LocalDateTime.now(), 1L, true
        ));

        verify(metrics).recordProjection("dropped");
    }
}
