package com.ekusys.exam.runtime.service;

import com.ekusys.exam.runtime.config.TimeoutSubmissionProperties;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.SessionState;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskCandidate;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskRow;
import com.ekusys.exam.runtime.service.SubmissionFinalPayloadService.EncodedFinalAnswers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate claimTransactions;
    private final ThreadPoolTaskExecutor executor;

    public TimeoutSubmissionCoordinator(
        TimeoutTaskRepository tasks,
        TimeoutSubmissionProperties properties,
        TimeoutSubmissionBackoffPolicy backoff,
        TimeoutSubmissionMetrics metrics,
        ExamSnapshotService snapshots,
        SubmissionFinalPayloadService finalPayloads,
        RuntimeOutboxService outbox,
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        @Qualifier("timeoutSubmissionExecutor") ThreadPoolTaskExecutor executor
    ) {
        this.tasks = tasks;
        this.properties = properties;
        this.backoff = backoff;
        this.metrics = metrics;
        this.snapshots = snapshots;
        this.finalPayloads = finalPayloads;
        this.outbox = outbox;
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
    }

    public int processDue() {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(properties.safeMaxRunMs()).toNanos();
        int reconciled = tasks.reconcileMissingTasks(properties.safeReconcileBatchSize());
        metrics.increment("reconciled", reconciled);
        AtomicInteger completed = new AtomicInteger();

        while (System.nanoTime() < deadlineNanos) {
            List<TimeoutTaskClaim> claims = claimBatch();
            if (claims.isEmpty()) {
                break;
            }
            List<CompletableFuture<Void>> futures = claims.stream()
                .map(claim -> CompletableFuture.runAsync(() -> {
                    if (process(claim)) {
                        completed.incrementAndGet();
                    }
                }, executor))
                .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }
        refreshBacklog();
        return completed.get();
    }

    public String ensureExpiredTask(Long sessionId, Long examId, Long studentId,
                                    LocalDateTime deadline) {
        LocalDateTime effectiveDeadline = deadline == null
            ? jdbc.queryForObject(
                "select deadline_time from exam_session where id=?",
                LocalDateTime.class,
                sessionId
            )
            : deadline;
        if (effectiveDeadline == null) {
            throw new IllegalStateException("考试会话缺少截止时间");
        }
        String result = transactions.execute(status -> {
            tasks.ensureTask(sessionId, examId, studentId, effectiveDeadline);
            TaskRow task = tasks.lockBySession(sessionId);
            if (task == null) {
                throw new IllegalStateException("超时交卷任务不存在");
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
            List<TaskCandidate> candidates = tasks.lockClaimable(properties.safeBatchSize());
            List<TimeoutTaskClaim> claims = new ArrayList<>(candidates.size());
            for (TaskCandidate candidate : candidates) {
                if (candidate.attemptCount() >= properties.safeMaxAttempts()) {
                    if (tasks.markAttemptsExhaustedLocked(
                        candidate.id(), properties.safeMaxAttempts(), "处理进程连续失联，已达到最大尝试次数"
                    ) != 1) {
                        throw new IllegalStateException("超时交卷任务尝试次数封顶失败");
                    }
                    metrics.increment("failed");
                    metrics.increment("attempts_exhausted");
                    continue;
                }
                String token = UUID.randomUUID().toString();
                if (tasks.markProcessing(candidate.id(), token, properties.safeLeaseMs()) != 1) {
                    metrics.increment("claim_conflict");
                    continue;
                }
                SessionState session = tasks.lockSession(candidate.sessionId());
                if (session == null) {
                    if (tasks.markInvalidStateFailed(candidate.id(), token, "考试会话不存在") != 1) {
                        throw new IllegalStateException("无效会话任务失败标记冲突");
                    }
                    metrics.increment("invalid_session");
                    continue;
                }
                if ("SUBMITTED".equals(session.status())) {
                    if (tasks.markDone(candidate.id(), token) != 1) {
                        throw new IllegalStateException("已提交会话任务完成标记冲突");
                    }
                    metrics.increment("already_submitted");
                    continue;
                }
                if (tasks.claimSessionForTimeout(candidate.sessionId()) != 1) {
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
                    candidate.dueAt(), token, candidate.attemptCount() + 1
                ));
            }
            metrics.increment("claimed", claims.size());
            return List.copyOf(claims);
        });
        return result == null ? List.of() : result;
    }

    private boolean process(TimeoutTaskClaim claim) {
        long started = System.nanoTime();
        try {
            SnapshotDraft draft = snapshots.loadLatestDraft(claim.examId(), claim.studentId());
            EncodedFinalAnswers encoded = finalPayloads.encode(draft.answers(), draft.version());
            Boolean completed = transactions.execute(status -> finalizeClaim(claim, encoded));
            if (!Boolean.TRUE.equals(completed)) {
                metrics.increment("stale_claim");
                metrics.recordFinalization(Duration.ofNanos(System.nanoTime() - started), "stale");
                return false;
            }
            LocalDateTime completedAt = dbNow();
            metrics.increment("completed");
            metrics.recordCompletionLatency(claim.dueAt(), completedAt);
            metrics.recordFinalization(Duration.ofNanos(System.nanoTime() - started), "success");
            return true;
        } catch (RuntimeException exception) {
            handleFailure(claim, exception);
            metrics.recordFinalization(Duration.ofNanos(System.nanoTime() - started), "failure");
            return false;
        }
    }

    private boolean finalizeClaim(TimeoutTaskClaim claim, EncodedFinalAnswers encoded) {
        TaskRow task = tasks.lockById(claim.taskId());
        LocalDateTime now = dbNow();
        if (task == null || !task.ownedBy(claim.claimToken())
            || task.leaseUntil() == null || !task.leaseUntil().isAfter(now)) {
            return false;
        }
        SessionState session = tasks.lockSession(claim.sessionId());
        if (session == null) {
            throw new IllegalStateException("考试会话不存在");
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

        Long submissionId = jdbc.queryForObject(
            "select id from submission where exam_id=? and student_id=?",
            Long.class, claim.examId(), claim.studentId()
        );
        if (submissionId == null) {
            throw new IllegalStateException("考试提交记录不存在");
        }
        finalPayloads.store(submissionId, "TIMEOUT", encoded);
        int submissionUpdated = jdbc.update(
            """
                update submission
                   set status='PROCESSING',submitted_at=current_timestamp(3),timeout_submit=1,
                       update_time=current_timestamp(3)
                 where id=? and status='IN_PROGRESS'
                """,
            submissionId
        );
        if (submissionUpdated != 1) {
            throw new IllegalStateException("超时交卷提交记录状态冲突");
        }
        outbox.submissionAccepted(submissionId);
        if (tasks.markSessionSubmitted(claim.sessionId()) != 1) {
            throw new IllegalStateException("超时交卷会话状态更新失败");
        }
        if (tasks.markDone(claim.taskId(), claim.claimToken()) != 1) {
            throw new IllegalStateException("超时交卷任务完成标记失败");
        }
        snapshots.clearAfterCommit(claim.examId(), claim.studentId());
        return true;
    }

    private void handleFailure(TimeoutTaskClaim claim, RuntimeException exception) {
        long delayMs = backoff.delayMillis(claim.attempt());
        LocalDateTime nextRetryAt = dbNow().plusNanos(delayMs * 1_000_000L);
        Integer updated = transactions.execute(status -> tasks.markFailure(
            claim.taskId(), claim.claimToken(), nextRetryAt,
            properties.safeMaxAttempts(), exceptionMessage(exception)
        ));
        if (updated == null || updated == 0) {
            metrics.increment("stale_failure");
            log.warn("Timeout submission failure ignored because lease changed: taskId={}", claim.taskId(), exception);
            return;
        }
        if (claim.attempt() >= properties.safeMaxAttempts()) {
            metrics.increment("failed");
            log.error("Timeout submission exhausted retries: taskId={}, sessionId={}, attempts={}",
                claim.taskId(), claim.sessionId(), claim.attempt(), exception);
        } else {
            metrics.increment("retry");
            log.warn("Timeout submission retry scheduled: taskId={}, sessionId={}, attempt={}, delayMs={}",
                claim.taskId(), claim.sessionId(), claim.attempt(), delayMs, exception);
        }
    }

    private void refreshBacklog() {
        try {
            metrics.updateBacklog(tasks.backlog());
        } catch (RuntimeException exception) {
            metrics.increment("metrics_unavailable");
            log.debug("Timeout submission backlog metrics unavailable", exception);
        }
    }

    private String exceptionMessage(Throwable exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
    }

    private record TimeoutTaskClaim(Long taskId, Long sessionId, Long examId, Long studentId,
                                    LocalDateTime dueAt, String claimToken, int attempt) {
    }
}
