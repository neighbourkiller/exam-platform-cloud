package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.runtime.config.TimeoutSubmissionProperties;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.SessionState;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskCandidate;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskRow;
import com.ekusys.exam.runtime.service.SubmissionFinalPayloadService.EncodedFinalAnswers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TimeoutSubmissionCoordinatorTest {
    private TimeoutTaskRepository tasks;
    private TimeoutSubmissionProperties properties;
    private TimeoutSubmissionMetrics metrics;
    private ExamSnapshotService snapshots;
    private SubmissionFinalPayloadService finalPayloads;
    private RuntimeOutboxService outbox;
    private JdbcTemplate jdbc;
    private ThreadPoolTaskExecutor executor;
    private TimeoutSubmissionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        tasks = mock(TimeoutTaskRepository.class);
        properties = new TimeoutSubmissionProperties();
        properties.setBatchSize(1);
        properties.setWorkerCount(1);
        properties.setMaxRunMs(1_000L);
        metrics = mock(TimeoutSubmissionMetrics.class);
        snapshots = mock(ExamSnapshotService.class);
        finalPayloads = mock(SubmissionFinalPayloadService.class);
        outbox = mock(RuntimeOutboxService.class);
        jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        coordinator = new TimeoutSubmissionCoordinator(
            tasks, properties, new TimeoutSubmissionBackoffPolicy(properties), metrics,
            snapshots, finalPayloads, outbox, jdbc, transactions, executor
        );
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void expiredLeaseCannotFinalizePayloadOrEvent() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        LocalDateTime dueAt = now.minusSeconds(1);
        TaskCandidate candidate = new TaskCandidate(
            1L, 1L, 2L, 3L, dueAt, "PENDING", 0, null
        );
        AtomicReference<String> token = new AtomicReference<>();
        when(tasks.lockClaimable(1)).thenReturn(List.of(candidate), List.of());
        when(tasks.markProcessing(eq(1L), anyString(), eq(60_000L))).thenAnswer(invocation -> {
            token.set(invocation.getArgument(1));
            return 1;
        });
        when(tasks.lockSession(1L)).thenReturn(
            new SessionState(1L, 2L, 3L, "ANSWERING", dueAt)
        );
        when(tasks.claimSessionForTimeout(1L)).thenReturn(1);
        when(snapshots.loadLatestDraft(2L, 3L)).thenReturn(
            new SnapshotDraft(Map.of(10L, "A"), 1L, now)
        );
        EncodedFinalAnswers encoded = new EncodedFinalAnswers(1L, new byte[] {1}, "hash");
        when(finalPayloads.encode(any(Map.class), eq(1L))).thenReturn(encoded);
        when(tasks.lockById(1L)).thenAnswer(invocation -> new TaskRow(
            1L, 1L, 2L, 3L, dueAt, "PROCESSING", token.get(),
            now.minusNanos(1), 1, null
        ));
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(now);

        int completed = coordinator.processDue();

        assertThat(completed).isZero();
        verify(finalPayloads, never()).store(anyLong(), anyString(), any());
        verify(outbox, never()).submissionAccepted(anyLong());
        verify(tasks, never()).markSessionSubmitted(anyLong());
        verify(metrics).increment("stale_claim");
    }

    @Test
    void exhaustedRecoveredTaskIsFailedWithoutAnotherClaim() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        TaskCandidate candidate = new TaskCandidate(
            1L, 1L, 2L, 3L, now.minusMinutes(1), "PROCESSING", 12,
            now.minusSeconds(1)
        );
        when(tasks.lockClaimable(anyInt())).thenReturn(List.of(candidate));
        when(tasks.markAttemptsExhaustedLocked(
            1L, 12, "处理进程连续失联，已达到最大尝试次数"
        )).thenReturn(1);

        int completed = coordinator.processDue();

        assertThat(completed).isZero();
        verify(tasks).markAttemptsExhaustedLocked(
            1L, 12, "处理进程连续失联，已达到最大尝试次数"
        );
        verify(tasks, never()).markProcessing(anyLong(), anyString(), anyLong());
        verify(metrics).increment("attempts_exhausted");
    }
}
