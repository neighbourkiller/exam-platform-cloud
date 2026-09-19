package com.ekusys.exam.runtime.service;

import com.ekusys.exam.runtime.config.TimeoutSubmissionProperties;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService.SubmissionAcceptedContext;
import com.ekusys.exam.runtime.messaging.SubmissionAcceptedReceipt;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.SessionState;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskCandidate;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskRow;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TimeoutHandoffState;
import com.ekusys.exam.runtime.observation.TimeoutSubmissionObservation;
import com.ekusys.exam.runtime.service.SubmissionFinalPayloadService.EncodedFinalAnswers;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TimeoutSubmissionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TimeoutSubmissionCoordinator.class);

    private final TimeoutTaskRepository tasks;
    private final TimeoutSubmissionProperties properties;
    private final TimeoutSubmissionBackoffPolicy backoff;
    private final TimeoutSubmissionMetrics metrics;
    private final ExamSnapshotService snapshots;
    private final SubmissionFinalPayloadService finalPayloads;
    private final RuntimeOutboxService outbox;
    private final SubmissionStatusProjectionService projectionService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate finalizationTransactions;
    private final TransactionTemplate reconcileTransactions;
    private final TransactionTemplate claimTransactions;
    private final ThreadPoolTaskExecutor executor;
    private final ThreadPoolTaskScheduler leaseScheduler;
    private final TimeoutSubmissionObservation observation;
    private final AtomicBoolean jobRunning = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile long lastBacklogRefreshNanos = Long.MIN_VALUE;
    private volatile long lastReconcileNanos = Long.MIN_VALUE;
    private volatile long reconcileCursor;
    private volatile int reconcileShardIndex = -1;
    private volatile int reconcileShardTotal = -1;

    @Autowired
    public TimeoutSubmissionCoordinator(
        TimeoutTaskRepository tasks,
        TimeoutSubmissionProperties properties,
        TimeoutSubmissionBackoffPolicy backoff,
        TimeoutSubmissionMetrics metrics,
        ExamSnapshotService snapshots,
        SubmissionFinalPayloadService finalPayloads,
        RuntimeOutboxService outbox,
        SubmissionStatusProjectionService projectionService,
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        @Qualifier("timeoutSubmissionFinalizationTransactionTemplate")
        TransactionTemplate finalizationTransactions,
        @Qualifier("timeoutSubmissionExecutor") ThreadPoolTaskExecutor executor,
        @Qualifier("timeoutSubmissionLeaseScheduler") ThreadPoolTaskScheduler leaseScheduler,
        TimeoutSubmissionObservation observation
    ) {
        this.tasks = tasks;
        this.properties = properties;
        this.backoff = backoff;
        this.metrics = metrics;
        this.snapshots = snapshots;
        this.finalPayloads = finalPayloads;
        this.outbox = outbox;
        this.projectionService = projectionService;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.finalizationTransactions = finalizationTransactions;
        this.reconcileTransactions = new TransactionTemplate(
            Objects.requireNonNull(
                transactions.getTransactionManager(),
                "Timeout submission reconciliation transaction manager is required"
            )
        );
        this.reconcileTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.reconcileTransactions.setTimeout(properties.safeFinalizationTransactionTimeoutSeconds());
        this.claimTransactions = new TransactionTemplate(
            Objects.requireNonNull(
                transactions.getTransactionManager(),
                "Timeout submission transaction manager is required"
            )
        );
        this.claimTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.executor = executor;
        this.leaseScheduler = leaseScheduler;
        this.observation = observation == null ? TimeoutSubmissionObservation.NOOP : observation;
    }

    public TimeoutSubmissionCoordinator(
        TimeoutTaskRepository tasks,
        TimeoutSubmissionProperties properties,
        TimeoutSubmissionBackoffPolicy backoff,
        TimeoutSubmissionMetrics metrics,
        ExamSnapshotService snapshots,
        SubmissionFinalPayloadService finalPayloads,
        RuntimeOutboxService outbox,
        SubmissionStatusProjectionService projectionService,
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        @Qualifier("timeoutSubmissionExecutor") ThreadPoolTaskExecutor executor,
        @Qualifier("timeoutSubmissionLeaseScheduler") ThreadPoolTaskScheduler leaseScheduler
    ) {
        this(tasks, properties, backoff, metrics, snapshots, finalPayloads, outbox,
            projectionService, jdbc, transactions, transactions, executor, leaseScheduler,
            TimeoutSubmissionObservation.NOOP);
    }

    /** Compatibility constructor retained for isolated tests and rolling callers. */
    public TimeoutSubmissionCoordinator(
        TimeoutTaskRepository tasks,
        TimeoutSubmissionProperties properties,
        TimeoutSubmissionBackoffPolicy backoff,
        TimeoutSubmissionMetrics metrics,
        ExamSnapshotService snapshots,
        SubmissionFinalPayloadService finalPayloads,
        RuntimeOutboxService outbox,
        SubmissionStatusProjectionService projectionService,
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        @Qualifier("timeoutSubmissionExecutor") ThreadPoolTaskExecutor executor,
        @Qualifier("timeoutSubmissionLeaseScheduler") ThreadPoolTaskScheduler leaseScheduler,
        TimeoutSubmissionObservation observation
    ) {
        this(tasks, properties, backoff, metrics, snapshots, finalPayloads, outbox,
            projectionService, jdbc, transactions, transactions, executor, leaseScheduler,
            observation);
    }

    public int processDue(int shardIndex, int shardTotal) {
        validateShard(shardIndex, shardTotal);
        if (!jobRunning.compareAndSet(false, true)) {
            metrics.increment("job_overlap");
            return 0;
        }
        try {
            return processDueWithinBudget(shardIndex, shardTotal);
        } finally {
            jobRunning.set(false);
        }
    }

    private int processDueWithinBudget(int shardIndex, int shardTotal) {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(properties.safeMaxRunMs()).toNanos();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger ownPending = new AtomicInteger();
        AtomicInteger crossPending = new AtomicInteger();
        AtomicInteger ownLeaseRecovered = new AtomicInteger();
        AtomicInteger crossLeaseRecovered = new AtomicInteger();
        BlockingQueue<TaskExecution> completions = new LinkedBlockingQueue<>();
        List<TaskExecution> active = new ArrayList<>();

        while (System.nanoTime() < deadlineNanos) {
            TaskExecution done;
            while ((done = completions.poll()) != null) {
                active.remove(done);
            }
            int capacity = properties.safeWorkerCount() - inFlight.get();
            if (capacity > 0) {
                int claimLimit = Math.min(properties.safeClaimSize(), capacity);
                List<TimeoutTaskClaim> claims = claimBatch(
                    shardIndex, shardTotal, claimLimit,
                    ownPending, crossPending, ownLeaseRecovered, crossLeaseRecovered
                );
                if (!claims.isEmpty()) {
                    claimed.addAndGet(claims.size());
                    boolean rejected = false;
                    for (int index = 0; index < claims.size(); index += 1) {
                        TimeoutTaskClaim claim = claims.get(index);
                        TaskExecution execution = new TaskExecution(claim, completions, completed);
                        inFlight.incrementAndGet();
                        try {
                            execution.register(executor.submit(execution::run));
                            active.add(execution);
                        } catch (RejectedExecutionException exception) {
                            execution.reject();
                            metrics.increment("worker_rejected");
                            log.warn("Timeout submission worker rejected a claimed task", exception);
                            handleFailure(claim, exception);
                            active.forEach(other -> cancelAndFail(
                                other, new IllegalStateException("超时交卷工作线程被拒绝", exception)
                            ));
                            active.clear();
                            for (int remaining = index + 1; remaining < claims.size(); remaining += 1) {
                                handleFailure(
                                    claims.get(remaining),
                                    new IllegalStateException("超时交卷工作线程被拒绝", exception)
                                );
                            }
                            rejected = true;
                            break;
                        }
                    }
                    if (rejected) {
                        break;
                    }
                    continue;
                }
            }

            if (active.isEmpty()) {
                break;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                cancelOutstanding(active, new IllegalStateException("超时交卷批次超过任务总预算"));
                metrics.increment("job_budget_exhausted");
                break;
            }
            TaskExecution finished;
            try {
                finished = completions.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancelOutstanding(active, exception);
                break;
            }
            if (finished == null) {
                metrics.increment("job_budget_exhausted");
                cancelOutstanding(active, new IllegalStateException("超时交卷批次超过任务总预算"));
                break;
            }
            active.remove(finished);
        }
        if (!active.isEmpty()) {
            cancelOutstanding(active, new IllegalStateException("超时交卷任务未在当前执行窗口完成"));
        }
        refreshBacklogIfDue();
        reconcileIfDue(shardIndex, shardTotal, deadlineNanos);
        log.info("Timeout submission round finished: shardIndex={}, shardTotal={}, claimed={}, completed={}, "
                + "ownPending={}, crossPending={}, ownLeaseRecovered={}, crossLeaseRecovered={}",
            shardIndex, shardTotal, claimed.get(), completed.get(),
            ownPending.get(), crossPending.get(), ownLeaseRecovered.get(), crossLeaseRecovered.get());
        return completed.get();
    }

    public String ensureExpiredTask(Long sessionId, Long examId, Long studentId,
                                    LocalDateTime deadline) {
        TimeoutHandoffState handoff = tasks.findHandoffState(sessionId);
        LocalDateTime effectiveDeadline = handoff != null && handoff.deadline() != null
            ? handoff.deadline()
            : deadline;
        if (effectiveDeadline == null) {
            throw new IllegalStateException("考试会话缺少截止时间");
        }
        if (handoff != null && handoff.isDurablyDue(examId, studentId)) {
            metrics.increment("handoff_fast_path");
            return "SUBMITTING";
        }
        if (handoff == null || handoff.taskId() == null) {
            tasks.repairTaskFromSession(sessionId);
        }
        String result = transactions.execute(status -> {
            TaskRow task = tasks.lockBySession(sessionId);
            if (task == null) {
                throw new IllegalStateException("超时交卷任务不存在");
            }
            if (!examId.equals(task.examId()) || !studentId.equals(task.studentId())) {
                throw new IllegalStateException("超时交卷任务与考试会话不匹配");
            }
            SessionState session = tasks.lockSession(sessionId);
            if (session == null) {
                throw new IllegalStateException("考试会话不存在");
            }
            if ("SUBMITTED".equals(session.status())) {
                if (!"DONE".equals(task.status()) && tasks.markDoneLocked(task.id()) != 1) {
                    throw new IllegalStateException("已提交会话的超时任务完成标记失败");
                }
                return "PROCESSING";
            }
            if ("DONE".equals(task.status())) {
                throw new IllegalStateException("超时交卷任务与会话状态不一致");
            }
            if ("PENDING".equals(task.status()) && tasks.expeditePendingLocked(task.id()) != 1) {
                throw new IllegalStateException("超时交卷任务加速失败");
            }
            return "SUBMITTING";
        });
        return result == null ? "SUBMITTING" : result;
    }

    private List<TimeoutTaskClaim> claimBatch(int shardIndex, int shardTotal, int claimLimit,
                                              AtomicInteger ownPending, AtomicInteger crossPending,
                                              AtomicInteger ownLeaseRecovered, AtomicInteger crossLeaseRecovered) {
        List<TimeoutTaskClaim> result = claimTransactions.execute(status -> {
            TimeoutTaskRepository.ClaimSelection selection = tasks.lockClaimable(
                shardIndex, shardTotal, claimLimit, properties.safeCrossShardDelayMs());
            if (selection == null) {
                return List.of();
            }
            List<TaskCandidate> candidates = selection.candidates();
            List<TimeoutTaskClaim> claims = new ArrayList<>(candidates.size());
            for (TaskCandidate candidate : candidates) {
                if (candidate.attemptCount() >= properties.safeMaxAttempts()) {
                    SessionState session = tasks.lockSession(candidate.sessionId());
                    if (session != null && "ANSWERING".equals(session.status())
                        && tasks.claimSessionForTimeout(candidate.sessionId()) != 1) {
                        throw new IllegalStateException("尝试次数封顶任务无法锁定会话");
                    }
                    if (tasks.markAttemptsExhaustedLocked(
                        candidate.id(), properties.safeMaxAttempts(), "处理进程连续失联，已达到最大尝试次数"
                    ) != 1) {
                        throw new IllegalStateException("超时交卷任务尝试次数封顶失败");
                    }
                    metrics.increment("failed");
                    metrics.increment("attempts_exhausted");
                    if (session != null && tasks.markSessionSubmissionFailed(candidate.sessionId()) != 1) {
                        throw new IllegalStateException("尝试次数封顶任务无法进入永久失败状态");
                    }
                    continue;
                }
                String token = UUID.randomUUID().toString();
                if (tasks.markProcessing(candidate.id(), token, properties.safeLeaseMs()) != 1) {
                    metrics.increment("claim_conflict");
                    continue;
                }
                if (tasks.claimSessionForTimeout(candidate.sessionId()) != 1) {
                    SessionState session = tasks.lockSession(candidate.sessionId());
                    if (session != null && "SUBMITTED".equals(session.status())) {
                        if (tasks.markDoneLocked(candidate.id()) != 1) {
                            throw new IllegalStateException("已提交会话任务完成标记冲突");
                        }
                        metrics.increment("already_submitted");
                        continue;
                    }
                    if (session == null) {
                        if (tasks.markInvalidStateFailed(candidate.id(), token, "考试会话不存在") != 1) {
                            throw new IllegalStateException("无效会话任务失败标记冲突");
                        }
                        metrics.increment("invalid_session");
                        continue;
                    }
                    if (tasks.markInvalidStateFailed(
                        candidate.id(), token, "会话状态不允许超时交卷: " + session.status()
                    ) != 1) {
                        throw new IllegalStateException("无效会话状态任务失败标记冲突");
                    }
                    metrics.increment("invalid_session_state");
                    continue;
                }
                boolean ownShard = Math.floorMod(candidate.id(), shardTotal) == shardIndex;
                if (candidate.recoveredLease()) {
                    metrics.increment("lease_recovered");
                    if (ownShard) {
                        metrics.increment("claim_lease_recovered_own");
                        ownLeaseRecovered.incrementAndGet();
                    } else {
                        metrics.increment("claim_lease_recovered_cross");
                        crossLeaseRecovered.incrementAndGet();
                    }
                } else if (ownShard) {
                    metrics.increment("claim_own_pending");
                    ownPending.incrementAndGet();
                } else {
                    metrics.increment("claim_cross_pending");
                    crossPending.incrementAndGet();
                }
                claims.add(new TimeoutTaskClaim(
                    candidate.id(), candidate.sessionId(), candidate.examId(), candidate.studentId(),
                    candidate.submissionId(), candidate.dueAt(), token, candidate.attemptCount() + 1
                ));
            }
            metrics.increment("claimed", claims.size());
            return List.copyOf(claims);
        });
        return result == null ? List.of() : result;
    }

    private boolean process(TimeoutTaskClaim claim) {
        long started = System.nanoTime();
        long taskDeadline = started + Duration.ofMillis(properties.safeTaskTimeoutMs()).toNanos();
        AtomicBoolean leaseLost = new AtomicBoolean();
        Duration renewInterval = Duration.ofMillis(properties.safeLeaseRenewIntervalMs());
        try {
            claim.registerRenewal(leaseScheduler.scheduleAtFixedRate(
                () -> renewLease(claim, leaseLost, started),
                Instant.now().plus(renewInterval),
                renewInterval
            ));
            observe(() -> observation.onClaimProcessingStarted(
                claim.examId(), claim.taskId(), claim.attempt(), claim.claimToken(),
                System.nanoTime() - started
            ), "claim_processing_started", claim.taskId());
            long sDraft = System.nanoTime();
            SnapshotDraft draft = snapshots.loadLatestDraft(claim.examId(), claim.studentId(), claim.submissionId());
            EncodedFinalAnswers encoded = finalPayloads.encode(draft.answers(), draft.version());
            metrics.recordStage("draft_load", Duration.ofNanos(System.nanoTime() - sDraft));
            requireWithinTaskBudget(claim, taskDeadline, leaseLost);
            long sTx = System.nanoTime();
            Boolean completed = finalizationTransactions.execute(status -> finalizeClaim(claim, encoded));
            metrics.recordStage("transaction_total", Duration.ofNanos(System.nanoTime() - sTx));
            if (!Boolean.TRUE.equals(completed)) {
                metrics.increment("stale_claim");
                metrics.recordFinalization(Duration.ofNanos(System.nanoTime() - started), "stale");
                return false;
            }
            try {
                TimeoutTaskRepository.CompletionTimes completion = tasks.completionTimes(claim.taskId());
                if (completion != null) {
                    metrics.recordCompletionLatency(completion.dueAt(), completion.completedAt());
                }
            } catch (RuntimeException metricsException) {
                // 提交已经完成，完成延迟查询失败不能把成功结果重新标记为失败。
                metrics.increment("metrics_unavailable");
                log.warn("Timeout submission completion latency unavailable after commit: taskId={}",
                    claim.taskId(), metricsException);
            }
            observe(() -> observation.onFinalizationCommitted(
                claim.examId(), claim.taskId(), claim.attempt(), claim.claimToken(),
                System.nanoTime() - started
            ), "finalization_committed", claim.taskId());
            metrics.increment("completed");
            metrics.recordFinalization(Duration.ofNanos(System.nanoTime() - started), "success");
            return true;
        } catch (RuntimeException exception) {
            handleFailure(claim, exception);
            metrics.recordFinalization(Duration.ofNanos(System.nanoTime() - started), "failure");
            return false;
        } finally {
            claim.cancelRenewal();
        }
    }

    private void renewLease(TimeoutTaskClaim claim, AtomicBoolean leaseLost, long startedNanos) {
        if (claim.cancellationRequested()) {
            return;
        }
        try {
            if (tasks.renewLease(claim.taskId(), claim.claimToken(), properties.safeLeaseMs()) != 1) {
                leaseLost.set(true);
                metrics.increment("lease_renew_lost");
            } else {
                observe(() -> observation.onLeaseRenewed(
                    claim.examId(), claim.taskId(), claim.attempt(), claim.claimToken(),
                    System.nanoTime() - startedNanos
                ), "lease_renewed", claim.taskId());
                metrics.increment("lease_renewed");
            }
        } catch (RuntimeException exception) {
            leaseLost.set(true);
            metrics.increment("lease_renew_failed");
            log.warn("Timeout submission lease renewal failed: taskId={}", claim.taskId(), exception);
        }
    }

    private void observe(Runnable callback, String eventType, Long taskId) {
        try {
            callback.run();
        } catch (RuntimeException exception) {
            // An optional test observer must not change the business result,
            // transaction outcome, or lease scheduler semantics.
            log.warn("Timeout submission observation failed: eventType={}, taskId={}",
                eventType, taskId, exception);
        }
    }

    private void requireWithinTaskBudget(TimeoutTaskClaim claim, long deadlineNanos,
                                         AtomicBoolean leaseLost) {
        if (claim.cancellationRequested()) {
            throw new IllegalStateException("超时交卷任务已取消");
        }
        if (leaseLost.get()) {
            throw new IllegalStateException("超时交卷任务租约续租失败");
        }
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos) {
            throw new IllegalStateException("超时交卷任务超过单任务运行预算");
        }
    }

    private void cancelOutstanding(List<TaskExecution> executions, Exception exception) {
        List<TaskExecution> copy = List.copyOf(executions);
        copy.forEach(execution -> cancelAndFail(execution, exception));
    }

    private void cancelAndFail(TaskExecution execution, Exception exception) {
        execution.cancelExecution();
        handleFailure(
            execution.claim(),
            exception instanceof RuntimeException runtimeException
                ? runtimeException
                : new IllegalStateException("超时交卷任务未能在当前执行窗口内启动或完成", exception)
        );
    }

    private boolean finalizeClaim(TimeoutTaskClaim claim, EncodedFinalAnswers encoded) {
        long sTaskLock = System.nanoTime();
        TaskRow task = tasks.lockById(claim.taskId());
        metrics.recordStage("task_lock", Duration.ofNanos(System.nanoTime() - sTaskLock));
        if (claim.cancellationRequested() || task == null || !task.ownedBy(claim.claimToken())) {
            return false;
        }
        // 过期任务可以在锁 Session 前快速放弃；这里只允许提前拒绝，不允许据此接受写入。
        if (task.dbNow() != null && task.leaseUntil() != null
            && !task.leaseUntil().isAfter(task.dbNow())) {
            return false;
        }
        long sSessionLock = System.nanoTime();
        SessionState session = tasks.lockSession(claim.sessionId());
        metrics.recordStage("session_lock", Duration.ofNanos(System.nanoTime() - sSessionLock));
        if (session == null) {
            throw new IllegalStateException("考试会话不存在");
        }
        if (!claim.examId().equals(session.examId()) || !claim.studentId().equals(session.studentId())) {
            throw new IllegalStateException("考试会话与超时任务不匹配");
        }
        // Task 和 Session 均已锁定后重新读取数据库时间，避免锁等待跨过截止或租约期限。
        LocalDateTime now = dbNow();
        if (claim.cancellationRequested()
            || task.leaseUntil() == null || !task.leaseUntil().isAfter(now)) {
            return false;
        }
        if ("SUBMITTED".equals(session.status())) {
            if (tasks.markDone(claim.taskId(), claim.claimToken()) != 1) {
                throw new IllegalStateException("已提交任务完成标记失败");
            }
            return true;
        }
        if (task.dueAt() == null || task.dueAt().isAfter(now)) {
            return false;
        }
        if (!"AUTO_SUBMITTING".equals(session.status())) {
            throw new IllegalStateException("超时交卷会话状态已变化: " + session.status());
        }
        if (session.deadline() == null || session.deadline().isAfter(now)) {
            return false;
        }

        Long submissionId = task.submissionId();
        if (submissionId == null) {
            List<SubmissionIdentity> subs = jdbc.query(
                "select id, exam_id, student_id from submission where exam_id=? and student_id=?",
                (rs, rowNum) -> new SubmissionIdentity(rs.getLong("id"), rs.getLong("exam_id"), rs.getLong("student_id")),
                claim.examId(), claim.studentId()
            );
            if (subs.isEmpty()) {
                throw new IllegalStateException("考试提交记录不存在");
            }
            SubmissionIdentity sub = subs.getFirst();
            if (!claim.examId().equals(sub.examId()) || !claim.studentId().equals(sub.studentId())) {
                throw new IllegalStateException("考试提交记录与超时任务不匹配");
            }
            submissionId = sub.id();
            tasks.backfillSubmissionId(claim.taskId(), submissionId);
        }

        long sPayload = System.nanoTime();
        finalPayloads.store(submissionId, "TIMEOUT", encoded, now);
        metrics.recordStage("payload_store", Duration.ofNanos(System.nanoTime() - sPayload));

        long sSub = System.nanoTime();
        int submissionUpdated = jdbc.update(
            """
                update submission
                   set status='PROCESSING',submitted_at=?,timeout_submit=1,
                       update_time=?
                 where id=? and status='IN_PROGRESS'
                """,
            now, now, submissionId
        );
        metrics.recordStage("submission_update", Duration.ofNanos(System.nanoTime() - sSub));
        if (submissionUpdated != 1) {
            throw new IllegalStateException("超时交卷提交记录状态冲突");
        }

        long sOutbox = System.nanoTime();
        SubmissionAcceptedReceipt receipt = outbox.submissionAcceptedKnown(new SubmissionAcceptedContext(
            submissionId,
            claim.examId(),
            claim.studentId(),
            "PROCESSING",
            now,
            true,
            "TIMEOUT",
            encoded.snapshotVersion(),
            encoded.sha256(),
            now,
            now
        ));
        metrics.recordStage("outbox_write", Duration.ofNanos(System.nanoTime() - sOutbox));

        long sStatus = System.nanoTime();
        if (tasks.markSessionSubmitted(claim.sessionId()) != 1) {
            throw new IllegalStateException("超时交卷会话状态更新失败");
        }
        if (tasks.markDone(claim.taskId(), claim.claimToken()) != 1) {
            throw new IllegalStateException("超时交卷任务完成标记失败");
        }
        metrics.recordStage("session_update", Duration.ofNanos(System.nanoTime() - sStatus));

        snapshots.clearAfterCommit(claim.examId(), claim.studentId());
        projectionService.recordSubmittedAfterCommit(receipt);
        return true;
    }

    private void handleFailure(TimeoutTaskClaim claim, RuntimeException exception) {
        long delayMs = backoff.delayMillis(claim.attempt());
        LocalDateTime nextRetryAt = dbNow().plusNanos(delayMs * 1_000_000L);
        boolean terminal = claim.attempt() >= properties.safeMaxAttempts();
        String incidentId = terminal ? UUID.randomUUID().toString() : null;
        Integer updated = transactions.execute(status -> {
            int changed = tasks.markFailure(
                claim.taskId(), claim.claimToken(), nextRetryAt,
                properties.safeMaxAttempts(), exceptionMessage(exception),
                failureCode(exception), incidentId
            );
            if (changed == 1 && terminal && tasks.markSessionSubmissionFailed(claim.sessionId()) != 1) {
                throw new IllegalStateException("永久失败会话状态更新失败");
            }
            return changed;
        });
        if (updated == null || updated == 0) {
            metrics.increment("stale_failure");
            log.warn("Timeout submission failure ignored because lease changed: taskId={}", claim.taskId(), exception);
            return;
        }
        if (terminal) {
            metrics.increment("failed");
            log.error("Timeout submission exhausted retries: taskId={}, sessionId={}, attempts={}",
                claim.taskId(), claim.sessionId(), claim.attempt(), exception);
        } else {
            metrics.increment("retry");
            log.warn("Timeout submission retry scheduled: taskId={}, sessionId={}, attempt={}, delayMs={}",
                claim.taskId(), claim.sessionId(), claim.attempt(), delayMs, exception);
        }
    }

    private void refreshBacklogIfDue() {
        long nowNanos = System.nanoTime();
        long refreshIntervalNanos = TimeUnit.MILLISECONDS.toNanos(
            properties.safeBacklogRefreshIntervalMs()
        );
        long lastRefreshNanos = lastBacklogRefreshNanos;
        if (lastRefreshNanos != Long.MIN_VALUE
            && nowNanos - lastRefreshNanos < refreshIntervalNanos) {
            return;
        }
        try {
            metrics.updateBacklog(tasks.backlog());
            lastBacklogRefreshNanos = nowNanos;
        } catch (RuntimeException exception) {
            metrics.increment("metrics_unavailable");
            log.debug("Timeout submission backlog metrics unavailable", exception);
        }
    }

    private static void validateShard(int shardIndex, int shardTotal) {
        if (shardTotal < 1 || shardIndex < 0 || shardIndex >= shardTotal) {
            throw new IllegalArgumentException(
                "非法超时交卷分片参数: shardIndex=" + shardIndex + ", shardTotal=" + shardTotal
            );
        }
    }

    private String exceptionMessage(Throwable exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private String failureCode(Throwable exception) {
        String name = exception.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
        if (name.contains("TIMEOUT")) return "DEPENDENCY_TIMEOUT";
        if (name.contains("DATAACCESS") || name.contains("SQL")) return "DATABASE_FAILURE";
        if (name.contains("REDIS")) return "SNAPSHOT_STORE_FAILURE";
        return "TIMEOUT_SUBMISSION_FAILED";
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
    }

    private final class TaskExecution {
        private final TimeoutTaskClaim claim;
        private final BlockingQueue<TaskExecution> completions;
        private final AtomicInteger completed;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean slotReleased = new AtomicBoolean();
        private final AtomicReference<Future<?>> future = new AtomicReference<>();

        private TaskExecution(TimeoutTaskClaim claim, BlockingQueue<TaskExecution> completions,
                              AtomicInteger completed) {
            this.claim = claim;
            this.completions = completions;
            this.completed = completed;
        }

        private TimeoutTaskClaim claim() {
            return claim;
        }

        private void register(Future<?> submitted) {
            future.set(submitted);
            if (claim.cancellationRequested()) {
                cancelExecution();
            }
        }

        private void run() {
            if (!started.compareAndSet(false, true)) {
                finish();
                return;
            }
            try {
                boolean succeeded = process(claim);
                if (succeeded) {
                    completed.incrementAndGet();
                }
            } catch (RuntimeException exception) {
                metrics.increment("worker_failed");
                log.warn("Timeout submission worker failed: taskId={}", claim.taskId(), exception);
                handleFailure(claim, exception);
            } finally {
                finish();
            }
        }

        private void reject() {
            claim.cancelExecution();
            if (started.compareAndSet(false, true)) {
                finish();
            }
        }

        private void cancelExecution() {
            claim.cancelExecution();
            Future<?> submitted = future.get();
            if (submitted == null) {
                if (started.compareAndSet(false, true)) {
                    finish();
                }
                return;
            }
            if (!started.get()) {
                if (submitted.cancel(false) && started.compareAndSet(false, true)) {
                    finish();
                }
            } else {
                submitted.cancel(true);
            }
        }

        private void finish() {
            if (slotReleased.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
                completions.offer(this);
            }
        }
    }

    private void reconcileIfDue(int shardIndex, int shardTotal, long outerDeadlineNanos) {
        if (!properties.isReconcileEnabled()) {
            return;
        }
        long nowNanos = System.nanoTime();
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(properties.safeReconcileIntervalMs());
        if (lastReconcileNanos != Long.MIN_VALUE
            && nowNanos - lastReconcileNanos < intervalNanos) {
            return;
        }
        lastReconcileNanos = nowNanos;
        metrics.recordReconcileTriggered();
        if (reconcileShardIndex != shardIndex || reconcileShardTotal != shardTotal) {
            reconcileCursor = 0L;
            reconcileShardIndex = shardIndex;
            reconcileShardTotal = shardTotal;
        }
        long reconcileDeadline = Math.min(
            outerDeadlineNanos,
            nowNanos + TimeUnit.MILLISECONDS.toNanos(properties.safeReconcileMaxRunMs())
        );
        if (System.nanoTime() >= reconcileDeadline) {
            metrics.recordReconcileOutcome("budget_exhausted");
            return;
        }

        List<TimeoutTaskRepository.ReconcileCandidate> candidates;
        try {
            candidates = tasks.findReconcileCandidates(
                shardIndex, shardTotal, reconcileCursor, properties.safeReconcileBatchSize()
            );
        } catch (RuntimeException exception) {
            metrics.recordReconcileOutcome("failed");
            log.warn("Timeout submission reconciliation candidate scan failed: shard={}/{}",
                shardIndex, shardTotal, exception);
            return;
        }
        if (candidates == null) {
            candidates = List.of();
        }
        boolean pageFinished = candidates.size() < properties.safeReconcileBatchSize();
        int processed = 0;
        boolean budgetExhausted = false;
        for (TimeoutTaskRepository.ReconcileCandidate candidate : candidates) {
            if (System.nanoTime() >= reconcileDeadline) {
                metrics.recordReconcileOutcome("budget_exhausted");
                budgetExhausted = true;
                break;
            }
            TimeoutTaskRepository.ReconcileOutcome outcome = reconcileOne(candidate);
            processed += 1;
            reconcileCursor = candidate.sessionId();
            metrics.recordReconcileOutcome(outcome.name().toLowerCase(java.util.Locale.ROOT));
            if (outcome == TimeoutTaskRepository.ReconcileOutcome.REPAIRED) {
                log.info("Timeout submission task repaired: sessionId={}, examId={}, studentId={}",
                    candidate.sessionId(), candidate.examId(), candidate.studentId());
            } else if (outcome == TimeoutTaskRepository.ReconcileOutcome.SKIPPED) {
                if (candidate.taskId() == null) {
                    log.debug("Timeout submission reconciliation skipped candidate after concurrent state change: "
                            + "sessionId={}, examId={}, studentId={}",
                        candidate.sessionId(), candidate.examId(), candidate.studentId());
                } else {
                    log.warn("Timeout submission reconciliation found an inconsistent state and left it unchanged: "
                            + "sessionId={}, taskId={}, taskStatus={}, submissionStatus={}",
                        candidate.sessionId(), candidate.taskId(), candidate.taskStatus(),
                        candidate.submissionStatus());
                }
            }
        }
        if (pageFinished && processed == candidates.size()) {
            reconcileCursor = 0L;
        }
        if (!budgetExhausted) {
            metrics.recordReconcileCompleted();
        }
    }

    private TimeoutTaskRepository.ReconcileOutcome reconcileOne(
        TimeoutTaskRepository.ReconcileCandidate candidate
    ) {
        try {
            TimeoutTaskRepository.ReconcileOutcome outcome = reconcileTransactions.execute(status -> {
                int inserted = tasks.insertMissingTask(candidate);
                TaskRow task = tasks.lockBySession(candidate.sessionId());
                if (task == null) {
                    throw new IllegalStateException("对账候选缺少超时任务");
                }
                SessionState session = tasks.lockSession(candidate.sessionId());
                TimeoutTaskRepository.SubmissionFinalState submission =
                    tasks.lockSubmissionState(candidate.examId(), candidate.studentId());
                LocalDateTime now = dbNow();
                boolean eligible = session != null
                    && session.deadline() != null
                    && !session.deadline().isAfter(now)
                    && ("ANSWERING".equals(session.status()) || "AUTO_SUBMITTING".equals(session.status()))
                    && submission != null
                    && "IN_PROGRESS".equals(submission.status())
                    && !submission.hasFinalResult();
                if (!eligible) {
                    if (inserted > 0) {
                        status.setRollbackOnly();
                    }
                    return TimeoutTaskRepository.ReconcileOutcome.SKIPPED;
                }
                return inserted > 0
                    ? TimeoutTaskRepository.ReconcileOutcome.REPAIRED
                    : TimeoutTaskRepository.ReconcileOutcome.SKIPPED;
            });
            return outcome == null
                ? TimeoutTaskRepository.ReconcileOutcome.FAILED : outcome;
        } catch (RuntimeException exception) {
            log.warn("Timeout submission reconciliation candidate failed: sessionId={}",
                candidate.sessionId(), exception);
            return TimeoutTaskRepository.ReconcileOutcome.FAILED;
        }
    }

    private record TimeoutTaskClaim(Long taskId, Long sessionId, Long examId, Long studentId,
                                    Long submissionId, LocalDateTime dueAt, String claimToken,
                                    int attempt, AtomicBoolean cancellation,
                                    AtomicReference<ScheduledFuture<?>> renewal) {
        private TimeoutTaskClaim(Long taskId, Long sessionId, Long examId, Long studentId,
                                 Long submissionId, LocalDateTime dueAt, String claimToken,
                                 int attempt) {
            this(taskId, sessionId, examId, studentId, submissionId, dueAt, claimToken, attempt,
                new AtomicBoolean(), new AtomicReference<>());
        }

        private void registerRenewal(ScheduledFuture<?> scheduled) {
            renewal.set(scheduled);
            if (cancellation.get()) {
                scheduled.cancel(false);
            }
        }

        private boolean cancellationRequested() {
            return cancellation.get();
        }

        private void cancelExecution() {
            cancellation.set(true);
            cancelRenewal();
        }

        private void cancelRenewal() {
            ScheduledFuture<?> scheduled = renewal.getAndSet(null);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }

    private record SubmissionIdentity(Long id, Long examId, Long studentId) {
    }
}
