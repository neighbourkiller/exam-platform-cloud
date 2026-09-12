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
import com.ekusys.exam.runtime.service.SubmissionFinalPayloadService.EncodedFinalAnswers;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final TransactionTemplate claimTransactions;
    private final ThreadPoolTaskExecutor executor;
    private final ThreadPoolTaskScheduler leaseScheduler;
    private final AtomicBoolean jobRunning = new AtomicBoolean();
    private volatile long lastBacklogRefreshNanos = Long.MIN_VALUE;

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
        this.claimTransactions = new TransactionTemplate(
            Objects.requireNonNull(
                transactions.getTransactionManager(),
                "Timeout submission transaction manager is required"
            )
        );
        this.claimTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.executor = executor;
        this.leaseScheduler = leaseScheduler;
    }

    public int processDue() {
        if (!jobRunning.compareAndSet(false, true)) {
            metrics.increment("job_overlap");
            return 0;
        }
        try {
            return processDueWithinBudget();
        } finally {
            jobRunning.set(false);
        }
    }

    private int processDueWithinBudget() {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(properties.safeMaxRunMs()).toNanos();
        AtomicInteger completed = new AtomicInteger();

        while (System.nanoTime() < deadlineNanos) {
            List<TimeoutTaskClaim> claims = claimBatch();
            if (claims.isEmpty()) {
                break;
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>(claims.size());
            try {
                for (TimeoutTaskClaim claim : claims) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        if (process(claim)) {
                            completed.incrementAndGet();
                        }
                    }, executor));
                }
            } catch (RejectedExecutionException exception) {
                retryOutstandingClaims(claims, futures, exception);
                metrics.increment("worker_rejected");
                log.warn("Timeout submission worker rejected a claimed task", exception);
                break;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                retryOutstandingClaims(
                    claims, futures, new IllegalStateException("超时交卷批次超过任务总预算")
                );
                metrics.increment("job_budget_exhausted");
                break;
            }
            if (!awaitBatch(claims, futures, remainingNanos)) {
                break;
            }
        }
        refreshBacklogIfDue();
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

    private List<TimeoutTaskClaim> claimBatch() {
        List<TimeoutTaskClaim> result = claimTransactions.execute(status -> {
            List<TaskCandidate> candidates = tasks.lockClaimable(properties.safeClaimSize());
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
                if (candidate.recoveredLease()) {
                    metrics.increment("lease_recovered");
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
        ScheduledFuture<?> renewal = leaseScheduler.scheduleAtFixedRate(
            () -> renewLease(claim, leaseLost), Instant.now().plus(renewInterval), renewInterval
        );
        try {
            long sDraft = System.nanoTime();
            SnapshotDraft draft = snapshots.loadLatestDraft(claim.examId(), claim.studentId(), claim.submissionId());
            EncodedFinalAnswers encoded = finalPayloads.encode(draft.answers(), draft.version());
            metrics.recordStage("draft_load", Duration.ofNanos(System.nanoTime() - sDraft));
            requireWithinTaskBudget(taskDeadline, leaseLost);
            long sTx = System.nanoTime();
            Boolean completed = transactions.execute(status -> finalizeClaim(claim, encoded));
            metrics.recordStage("transaction_total", Duration.ofNanos(System.nanoTime() - sTx));
            if (!Boolean.TRUE.equals(completed)) {
                metrics.increment("stale_claim");
                metrics.recordFinalization(Duration.ofNanos(System.nanoTime() - started), "stale");
                return false;
            }
            metrics.increment("completed");
            metrics.recordFinalization(Duration.ofNanos(System.nanoTime() - started), "success");
            return true;
        } catch (RuntimeException exception) {
            handleFailure(claim, exception);
            metrics.recordFinalization(Duration.ofNanos(System.nanoTime() - started), "failure");
            return false;
        } finally {
            renewal.cancel(false);
        }
    }

    private void renewLease(TimeoutTaskClaim claim, AtomicBoolean leaseLost) {
        try {
            if (tasks.renewLease(claim.taskId(), claim.claimToken(), properties.safeLeaseMs()) != 1) {
                leaseLost.set(true);
                metrics.increment("lease_renew_lost");
            } else {
                metrics.increment("lease_renewed");
            }
        } catch (RuntimeException exception) {
            leaseLost.set(true);
            metrics.increment("lease_renew_failed");
            log.warn("Timeout submission lease renewal failed: taskId={}", claim.taskId(), exception);
        }
    }

    private void requireWithinTaskBudget(long deadlineNanos, AtomicBoolean leaseLost) {
        if (leaseLost.get()) {
            throw new IllegalStateException("超时交卷任务租约续租失败");
        }
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos) {
            throw new IllegalStateException("超时交卷任务超过单任务运行预算");
        }
    }

    private boolean awaitBatch(List<TimeoutTaskClaim> claims,
                               List<CompletableFuture<Void>> futures,
                               long remainingNanos) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(remainingNanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException exception) {
            metrics.increment("job_budget_exhausted");
            retryOutstandingClaims(claims, futures, exception);
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            retryOutstandingClaims(claims, futures, exception);
            return false;
        } catch (ExecutionException exception) {
            metrics.increment("worker_failed");
            log.warn("Timeout submission batch worker failed", exception.getCause());
            return true;
        }
    }

    private void retryOutstandingClaims(List<TimeoutTaskClaim> claims,
                                        List<CompletableFuture<Void>> futures,
                                        Exception exception) {
        List<TimeoutTaskClaim> outstanding = new ArrayList<>();
        for (int index = 0; index < claims.size(); index += 1) {
            if (index >= futures.size() || !futures.get(index).isDone()) {
                outstanding.add(claims.get(index));
            }
        }
        futures.forEach(future -> future.cancel(true));
        RuntimeException failure = exception instanceof RuntimeException runtimeException
            ? runtimeException
            : new IllegalStateException("超时交卷任务未能在当前执行窗口内启动或完成", exception);
        outstanding.forEach(claim -> handleFailure(claim, failure));
    }

    private boolean finalizeClaim(TimeoutTaskClaim claim, EncodedFinalAnswers encoded) {
        long sTaskLock = System.nanoTime();
        TaskRow task = tasks.lockById(claim.taskId());
        metrics.recordStage("task_lock", Duration.ofNanos(System.nanoTime() - sTaskLock));
        LocalDateTime now = task != null && task.dbNow() != null ? task.dbNow() : dbNow();
        if (task == null || !task.ownedBy(claim.claimToken())
            || task.leaseUntil() == null || !task.leaseUntil().isAfter(now)) {
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
        if ("SUBMITTED".equals(session.status())) {
            if (tasks.markDone(claim.taskId(), claim.claimToken()) != 1) {
                throw new IllegalStateException("已提交任务完成标记失败");
            }
            return true;
        }
        if (!"AUTO_SUBMITTING".equals(session.status())) {
            throw new IllegalStateException("超时交卷会话状态已变化: " + session.status());
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
            long missing = tasks.missingTaskCount();
            long inconsistent = tasks.inconsistentStateCount();
            long issues = missing + inconsistent;
            if (issues > 0) {
                metrics.increment("inconsistent_state", (int) Math.min(Integer.MAX_VALUE, issues));
                log.error(
                    "Timeout submission reconciliation found issues: missingTasks={}, inconsistentStates={}",
                    missing, inconsistent
                );
            }
            lastBacklogRefreshNanos = nowNanos;
        } catch (RuntimeException exception) {
            metrics.increment("metrics_unavailable");
            log.debug("Timeout submission backlog metrics unavailable", exception);
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

    private record TimeoutTaskClaim(Long taskId, Long sessionId, Long examId, Long studentId,
                                    Long submissionId, LocalDateTime dueAt, String claimToken, int attempt) {
    }

    private record SubmissionIdentity(Long id, Long examId, Long studentId) {
    }
}
