package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ekusys.exam.runtime.config.TimeoutSubmissionProperties;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.observation.TimeoutSubmissionObservation;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.ClaimSelection;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.SessionState;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskCandidate;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskRow;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TimeoutHandoffState;
import com.ekusys.exam.runtime.service.SubmissionFinalPayloadService.EncodedFinalAnswers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
    private SubmissionStatusProjectionService projectionService;
    private JdbcTemplate jdbc;
    private ThreadPoolTaskExecutor executor;
    private ThreadPoolTaskScheduler leaseScheduler;
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
        projectionService = mock(SubmissionStatusProjectionService.class);
        jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        leaseScheduler = new ThreadPoolTaskScheduler();
        leaseScheduler.setPoolSize(1);
        leaseScheduler.initialize();
        coordinator = new TimeoutSubmissionCoordinator(
            tasks, properties, new TimeoutSubmissionBackoffPolicy(properties), metrics,
            snapshots, finalPayloads, outbox, projectionService, jdbc, transactions, executor, leaseScheduler
        );
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        leaseScheduler.shutdown();
    }

    @Test
    void expiredLeaseCannotFinalizePayloadOrEvent() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        LocalDateTime dueAt = now.minusSeconds(1);
        TaskCandidate candidate = new TaskCandidate(
            1L, 1L, 2L, 3L, dueAt, "PENDING", 0, null
        );
        AtomicReference<String> token = new AtomicReference<>();
        when(tasks.lockClaimable(0, 1, 1, 10_000L)).thenReturn(claimSelection(candidate), claimSelection());
        when(tasks.markProcessing(eq(1L), anyString(), eq(30_000L))).thenAnswer(invocation -> {
            token.set(invocation.getArgument(1));
            return 1;
        });
        when(tasks.lockSession(1L)).thenReturn(
            new SessionState(1L, 2L, 3L, "ANSWERING", dueAt)
        );
        when(tasks.claimSessionForTimeout(1L)).thenReturn(1);
        when(snapshots.loadLatestDraft(eq(2L), eq(3L), any())).thenReturn(
            new SnapshotDraft(Map.of(10L, "A"), 1L, now)
        );
        EncodedFinalAnswers encoded = new EncodedFinalAnswers(1L, new byte[] {1}, "hash");
        when(finalPayloads.encode(any(Map.class), eq(1L))).thenReturn(encoded);
        when(tasks.lockById(1L)).thenAnswer(invocation -> new TaskRow(
            1L, 1L, 2L, 3L, null, dueAt, "PROCESSING", token.get(),
            now.minusNanos(1), 1, null, now
        ));
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(now);

        int completed = coordinator.processDue(0, 1);

        assertThat(completed).isZero();
        verify(finalPayloads, never()).store(anyLong(), anyString(), any());
        verify(outbox, never()).submissionAccepted(anyLong());
        verify(tasks, never()).markSessionSubmitted(anyLong());
        verify(tasks, never()).lockSession(anyLong());
        verify(metrics).increment("stale_claim");
    }

    @Test
    void exhaustedRecoveredTaskIsFailedWithoutAnotherClaim() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        TaskCandidate candidate = new TaskCandidate(
            1L, 1L, 2L, 3L, now.minusMinutes(1), "PROCESSING", 12,
            now.minusSeconds(1)
        );
        when(tasks.lockClaimable(anyInt(), anyInt(), anyInt(), anyLong())).thenReturn(claimSelection(candidate));
        when(tasks.markAttemptsExhaustedLocked(
            1L, 12, "处理进程连续失联，已达到最大尝试次数"
        )).thenReturn(1);

        int completed = coordinator.processDue(0, 1);

        assertThat(completed).isZero();
        verify(tasks).markAttemptsExhaustedLocked(
            1L, 12, "处理进程连续失联，已达到最大尝试次数"
        );
        verify(tasks, never()).markProcessing(anyLong(), anyString(), anyLong());
        verify(metrics).increment("attempts_exhausted");
    }

    @Test
    void claimCountIsBoundedByWorkerCountEvenWhenBatchIsTwoHundred() {
        properties.setBatchSize(200);
        properties.setWorkerCount(8);
        when(tasks.lockClaimable(0, 1, 8, 10_000L)).thenReturn(claimSelection());

        assertThat(coordinator.processDue(0, 1)).isZero();

        verify(tasks).lockClaimable(0, 1, 8, 10_000L);
    }

    @Test
    void consecutiveJobsRefreshBacklogOnlyOnceWithinConfiguredInterval() {
        properties.setBacklogRefreshIntervalMs(10_000L);
        when(tasks.lockClaimable(0, 1, 1, 10_000L)).thenReturn(claimSelection());

        assertThat(coordinator.processDue(0, 1)).isZero();
        assertThat(coordinator.processDue(0, 1)).isZero();

        verify(tasks, times(2)).lockClaimable(0, 1, 1, 10_000L);
        verify(tasks).backlog();
        verify(tasks).missingTaskCount();
        verify(tasks).inconsistentStateCount();
    }

    @Test
    void alreadyDuePendingTaskIsAcknowledgedWithoutLockingOrRewriting() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        LocalDateTime deadline = now.minusSeconds(1);
        when(tasks.findHandoffState(1L)).thenReturn(new TimeoutHandoffState(
            2L, 3L, "ANSWERING", deadline, 1L, "PENDING", deadline, now
        ));

        assertThat(coordinator.ensureExpiredTask(1L, 2L, 3L, deadline))
            .isEqualTo("SUBMITTING");

        verify(metrics).increment("handoff_fast_path");
        verify(tasks, never()).lockBySession(anyLong());
        verify(tasks, never()).lockSession(anyLong());
        verify(tasks, never()).expeditePendingLocked(anyLong());
        verify(tasks, never()).repairTaskFromSession(anyLong());
    }

    @Test
    void taskHardBudgetPreventsLateFinalPayloadWrite() {
        properties.setMaxRunMs(4_000L);
        properties.setTaskTimeoutMs(1_000L);
        properties.setLeaseRenewIntervalMs(5_000L);
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        AtomicReference<String> token = arrangeClaim(now);
        when(snapshots.loadLatestDraft(eq(2L), eq(3L), any())).thenAnswer(invocation -> {
            Thread.sleep(1_100L);
            return new SnapshotDraft(Map.of(10L, "A"), 1L, now);
        });
        when(finalPayloads.encode(any(Map.class), eq(1L))).thenReturn(
            new EncodedFinalAnswers(1L, new byte[] {1}, "hash")
        );
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(now);
        when(tasks.markFailure(
            eq(1L), anyString(), any(), anyInt(), anyString(), anyString(), any()
        )).thenReturn(1);

        assertThat(coordinator.processDue(0, 1)).isZero();

        verify(finalPayloads, never()).store(anyLong(), anyString(), any());
        verify(tasks).markFailure(
            eq(1L), eq(token.get()), any(), anyInt(), anyString(), anyString(), any()
        );
    }

    @Test
    void jobBudgetInterruptsOutstandingWorker() throws Exception {
        properties.setMaxRunMs(1_000L);
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        arrangeClaim(now);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        when(snapshots.loadLatestDraft(eq(2L), eq(3L), any())).thenAnswer(invocation -> {
            workerStarted.countDown();
            try {
                new CountDownLatch(1).await();
                throw new IllegalStateException("unreachable");
            } catch (InterruptedException exception) {
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("worker interrupted", exception);
            }
        });
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(now);
        when(tasks.markFailure(
            eq(1L), anyString(), any(), anyInt(), anyString(), anyString(), any()
        )).thenReturn(1);

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(
            () -> coordinator.processDue(0, 1)
        );

        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(run.get(2, TimeUnit.SECONDS)).isZero();
        assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void failedLeaseRenewalPreventsOldWorkerFromFinalizing() {
        properties.setMaxRunMs(4_000L);
        properties.setTaskTimeoutMs(3_000L);
        properties.setLeaseRenewIntervalMs(1_000L);
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        AtomicReference<String> token = arrangeClaim(now);
        when(tasks.renewLease(eq(1L), anyString(), anyLong())).thenReturn(0);
        when(snapshots.loadLatestDraft(eq(2L), eq(3L), any())).thenAnswer(invocation -> {
            Thread.sleep(1_150L);
            return new SnapshotDraft(Map.of(10L, "A"), 1L, now);
        });
        when(finalPayloads.encode(any(Map.class), eq(1L))).thenReturn(
            new EncodedFinalAnswers(1L, new byte[] {1}, "hash")
        );
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(now);
        when(tasks.markFailure(
            eq(1L), anyString(), any(), anyInt(), anyString(), anyString(), any()
        )).thenReturn(1);

        assertThat(coordinator.processDue(0, 1)).isZero();

        verify(tasks).renewLease(eq(1L), eq(token.get()), anyLong());
        verify(finalPayloads, never()).store(anyLong(), anyString(), any());
    }

    @Test
    void overlappingJobDoesNotClaimAnotherBatch() throws Exception {
        properties.setMaxRunMs(4_000L);
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        arrangeClaim(now);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        when(snapshots.loadLatestDraft(eq(2L), eq(3L), any())).thenAnswer(invocation -> {
            workerStarted.countDown();
            releaseWorker.await(2, TimeUnit.SECONDS);
            throw new IllegalStateException("test stop");
        });
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(now);
        when(tasks.markFailure(
            eq(1L), anyString(), any(), anyInt(), anyString(), anyString(), any()
        )).thenReturn(1);

        CompletableFuture<Integer> firstRun = CompletableFuture.supplyAsync(() -> coordinator.processDue(0, 1));
        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(coordinator.processDue(0, 1)).isZero();
        verify(metrics).increment("job_overlap");

        releaseWorker.countDown();
        firstRun.get(2, TimeUnit.SECONDS);
    }

    @Test
    void rejectedWorkerImmediatelyReturnsClaimForRetry() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        AtomicReference<String> token = arrangeClaim(now);
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(now);
        when(tasks.markFailure(
            eq(1L), anyString(), any(), anyInt(), anyString(), anyString(), any()
        )).thenReturn(1);
        executor.shutdown();

        assertThat(coordinator.processDue(0, 1)).isZero();

        verify(tasks).markFailure(
            eq(1L), eq(token.get()), any(), anyInt(), anyString(), anyString(), any()
        );
        verify(metrics).increment("worker_rejected");
    }

    @Test
    void consecutiveBatchesReuseSameShardParameters() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        LocalDateTime dueAt = now.minusSeconds(1);
        TaskCandidate candidate = new TaskCandidate(1L, 1L, 2L, 3L, dueAt, "PENDING", 0, null);
        when(tasks.lockClaimable(1, 3, 1, 10_000L)).thenReturn(claimSelection(candidate), claimSelection());
        when(tasks.lockSession(1L)).thenReturn(new SessionState(1L, 2L, 3L, "ANSWERING", dueAt));
        when(tasks.markProcessing(eq(1L), anyString(), eq(30_000L))).thenReturn(1);
        when(tasks.claimSessionForTimeout(1L)).thenReturn(1);
        when(snapshots.loadLatestDraft(eq(2L), eq(3L), any())).thenReturn(
            new SnapshotDraft(Map.of(10L, "A"), 1L, now)
        );
        when(finalPayloads.encode(any(Map.class), eq(1L))).thenReturn(
            new EncodedFinalAnswers(1L, new byte[] {1}, "hash")
        );
        when(tasks.lockById(1L)).thenAnswer(invocation -> new TaskRow(
            1L, 1L, 2L, 3L, null, dueAt, "PROCESSING", "foreign-token",
            now.plusSeconds(30), 1, null, now
        ));

        assertThat(coordinator.processDue(1, 3)).isZero();

        verify(tasks, times(2)).lockClaimable(1, 3, 1, 10_000L);
        verify(tasks, never()).lockClaimable(eq(0), eq(1), anyInt(), anyLong());
        verify(metrics).increment("stale_claim");
    }

    @Test
    void invalidShardParametersFailBeforeDatabaseAccess() {
        assertThatThrownBy(() -> coordinator.processDue(-1, 2))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.processDue(2, 2))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.processDue(0, 0))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(tasks, jdbc);
    }

    @Test
    void observerFailureCannotChangeBusinessFailureHandling() {
        TimeoutSubmissionObservation observer = mock(TimeoutSubmissionObservation.class);
        doThrow(new IllegalStateException("observer failed")).when(observer).onClaimProcessingStarted(
            anyLong(), anyLong(), anyInt(), anyString(), anyLong()
        );
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TimeoutSubmissionCoordinator isolated = new TimeoutSubmissionCoordinator(
            tasks, properties, new TimeoutSubmissionBackoffPolicy(properties), metrics,
            snapshots, finalPayloads, outbox, projectionService, jdbc,
            new TransactionTemplate(transactionManager), executor, leaseScheduler, observer
        );
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        AtomicReference<String> token = arrangeClaim(now);
        when(snapshots.loadLatestDraft(eq(2L), eq(3L), any())).thenThrow(
            new IllegalStateException("business failure")
        );
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(now);
        when(tasks.markFailure(
            eq(1L), anyString(), any(), anyInt(), anyString(), anyString(), any()
        )).thenReturn(1);

        assertThat(isolated.processDue(0, 1)).isZero();

        verify(tasks).markFailure(
            eq(1L), eq(token.get()), any(), anyInt(), anyString(), anyString(), any()
        );
    }


    private static ClaimSelection claimSelection(TaskCandidate... candidates) {
        int ownPending = 0;
        int crossPending = 0;
        int ownRecovered = 0;
        int crossRecovered = 0;
        for (TaskCandidate candidate : candidates) {
            if (candidate.recoveredLease()) {
                ownRecovered += 1;
            } else {
                ownPending += 1;
            }
        }
        return new ClaimSelection(List.of(candidates), ownPending, crossPending, ownRecovered, crossRecovered);
    }

    @Test
    void claimMetricsDistinguishOwnAndCrossShardSources() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 20, 0);
        LocalDateTime dueAt = now.minusSeconds(1);
        // 本片 PENDING（id=1, MOD(1,3)=1=shardIndex）
        TaskCandidate own = new TaskCandidate(1L, 1L, 2L, 3L, dueAt, "PENDING", 0, null);
        // 跨片 PENDING（id=2, MOD(2,3)=2 != 1）
        TaskCandidate cross = new TaskCandidate(2L, 2L, 2L, 4L, dueAt, "PENDING", 0, null);
        // 跨片过期租约（id=5, MOD(5,3)=2 != 1）
        TaskCandidate crossRecovered = new TaskCandidate(5L, 5L, 2L, 5L, dueAt, "PROCESSING", 1, now.minusSeconds(1));
        when(tasks.lockClaimable(eq(1), eq(3), anyInt(), eq(10_000L))).thenReturn(
            new ClaimSelection(List.of(own, cross, crossRecovered), 1, 1, 0, 1),
            new ClaimSelection(List.of(), 0, 0, 0, 0));
        when(tasks.lockSession(anyLong())).thenReturn(
            new SessionState(1L, 2L, 3L, "ANSWERING", dueAt));
        when(tasks.markProcessing(anyLong(), anyString(), anyLong())).thenReturn(1);
        when(tasks.claimSessionForTimeout(anyLong())).thenReturn(1);
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(now);

        coordinator.processDue(1, 3);

        verify(metrics).increment("claim_own_pending");
        verify(metrics).increment("claim_cross_pending");
        verify(metrics).increment("claim_lease_recovered_cross");
        verify(metrics, never()).increment("claim_lease_recovered_own");
    }

    private AtomicReference<String> arrangeClaim(LocalDateTime now) {
        LocalDateTime dueAt = now.minusSeconds(1);
        TaskCandidate candidate = new TaskCandidate(
            1L, 1L, 2L, 3L, dueAt, "PENDING", 0, null
        );
        AtomicReference<String> token = new AtomicReference<>();
        when(tasks.lockClaimable(0, 1, 1, 10_000L)).thenReturn(claimSelection(candidate), claimSelection());
        when(tasks.lockSession(1L)).thenReturn(
            new SessionState(1L, 2L, 3L, "ANSWERING", dueAt)
        );
        when(tasks.markProcessing(eq(1L), anyString(), eq(30_000L))).thenAnswer(invocation -> {
            token.set(invocation.getArgument(1));
            return 1;
        });
        when(tasks.claimSessionForTimeout(1L)).thenReturn(1);
        return token;
    }
}
