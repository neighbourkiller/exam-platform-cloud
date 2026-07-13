package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class SnapshotFlushCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SnapshotFlushCoordinator.class);

    private final SnapshotFlushQueue queue;
    private final SnapshotPersistenceService persistence;
    private final ExamAnswerInputValidator validator;
    private final ObjectMapper objectMapper;
    private final SnapshotFlushBackoffPolicy backoffPolicy;
    private final SnapshotFlushMetrics metrics;
    private final Executor executor;

    public SnapshotFlushCoordinator(SnapshotFlushQueue queue,
                                    SnapshotPersistenceService persistence,
                                    ExamAnswerInputValidator validator,
                                    ObjectMapper objectMapper,
                                    SnapshotFlushBackoffPolicy backoffPolicy,
                                    SnapshotFlushMetrics metrics,
                                    @Qualifier("snapshotFlushExecutor") Executor executor) {
        this.queue = queue;
        this.persistence = persistence;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.backoffPolicy = backoffPolicy;
        this.metrics = metrics;
        this.executor = executor;
    }

    public void flushDue() {
        SnapshotFlushClaimBatch batch;
        try {
            batch = queue.claimBatch();
        } catch (DataAccessException exception) {
            metrics.increment("redis_unavailable");
            log.warn("Redis snapshot flush queue unavailable", exception);
            return;
        }
        metrics.increment("lease_recovered", batch.recoveredLeases());
        metrics.increment("claimed", batch.claims().size());
        List<CompletableFuture<Void>> tasks = batch.claims().stream()
            .map(claim -> CompletableFuture.runAsync(() -> process(claim), executor))
            .toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        refreshBacklog();
    }

    public int reconcile() {
        try {
            int added = queue.reconcilePage();
            metrics.increment("reconciled", added);
            refreshBacklog();
            return added;
        } catch (DataAccessException exception) {
            metrics.increment("redis_unavailable");
            log.warn("Redis snapshot reconciliation unavailable", exception);
            return 0;
        }
    }

    public int cleanupFailed() {
        try {
            int deleted = queue.cleanupFailed();
            metrics.increment("failed_cleaned", deleted);
            refreshBacklog();
            return deleted;
        } catch (DataAccessException exception) {
            metrics.increment("redis_unavailable");
            log.warn("Redis snapshot failed-entry cleanup unavailable", exception);
            return 0;
        }
    }

    private void process(SnapshotFlushClaim claim) {
        SnapshotFlushRead read;
        try {
            read = queue.read(claim);
        } catch (DataAccessException exception) {
            metrics.increment("redis_unavailable");
            log.warn("Snapshot flush read failed; lease will recover: member={}", claim.member(), exception);
            return;
        }
        if (!read.leaseValid()) {
            metrics.increment("stale");
            return;
        }
        if (read.payload() == null || read.version() == null) {
            if (queue.acknowledgeMissing(claim)) {
                metrics.increment("missing");
            } else {
                metrics.increment("stale");
            }
            return;
        }

        try {
            SnapshotIdentity identity = SnapshotIdentity.parse(claim.member());
            SnapshotPayload payload = objectMapper.readValue(read.payload(), SnapshotPayload.class);
            validatePayload(identity, payload, read.version());
            long persistedVersion = persistence.persistDraft(
                identity.examId(), identity.studentId(), payload.answers(), payload.snapshotVersion()
            );
            if (persistedVersion >= 0 && persistedVersion < payload.snapshotVersion()) {
                throw new IllegalStateException("MySQL draft version did not cover snapshot");
            }
            long result = queue.acknowledge(claim, payload.snapshotVersion());
            if (result == 0) {
                metrics.increment("stale");
            } else {
                metrics.increment("persisted");
                if (result == 2) {
                    metrics.increment("newer_version");
                }
            }
        } catch (JsonProcessingException | SnapshotPayloadException | BusinessException exception) {
            handleFailure(claim, read.version(), exception, SnapshotFailureMode.POISON);
        } catch (DataAccessException exception) {
            handleFailure(claim, read.version(), exception, SnapshotFailureMode.TRANSIENT);
        } catch (RuntimeException exception) {
            handleFailure(claim, read.version(), exception, SnapshotFailureMode.RETRYABLE);
        }
    }

    private void validatePayload(SnapshotIdentity identity, SnapshotPayload payload, long claimedVersion) {
        if (payload.examId() == null || payload.studentId() == null
            || !payload.examId().equals(identity.examId())
            || !payload.studentId().equals(identity.studentId())
            || payload.snapshotVersion() <= 0
            || payload.snapshotVersion() != claimedVersion) {
            throw new SnapshotPayloadException("快照身份或版本不匹配");
        }
        validator.validateAnswers(payload.answers());
    }

    private void handleFailure(SnapshotFlushClaim claim, long version,
                               Throwable exception, SnapshotFailureMode mode) {
        try {
            int attempt = queue.nextAttempt(claim.member());
            long delay = backoffPolicy.delayMillis(attempt);
            SnapshotFailureResult result = queue.markFailure(claim, version, exception, mode, delay);
            if (!result.updated() || result.stale()) {
                metrics.increment("stale");
            } else if (result.quarantined()) {
                metrics.increment("quarantined");
                log.error("Snapshot flush quarantined: member={}, version={}, attempts={}",
                    claim.member(), version, result.attempts(), exception);
            } else {
                metrics.increment("retry");
                log.warn("Snapshot flush retry scheduled: member={}, version={}, attempts={}",
                    claim.member(), version, result.attempts(), exception);
            }
        } catch (DataAccessException redisException) {
            metrics.increment("redis_unavailable");
            log.warn("Snapshot flush failure could not be recorded; lease will recover: member={}",
                claim.member(), redisException);
        }
    }

    private void refreshBacklog() {
        try {
            metrics.updateBacklog(queue.backlog());
        } catch (DataAccessException exception) {
            metrics.increment("redis_unavailable");
            log.debug("Snapshot flush backlog metrics unavailable", exception);
        }
    }

    private record SnapshotIdentity(Long examId, Long studentId) {
        private static SnapshotIdentity parse(String member) {
            if (member == null || !member.matches("[1-9]\\d*:[1-9]\\d*")) {
                throw new SnapshotPayloadException("非法快照成员");
            }
            String[] parts = member.split(":", 2);
            try {
                return new SnapshotIdentity(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
            } catch (NumberFormatException exception) {
                throw new SnapshotPayloadException("非法快照成员");
            }
        }
    }

    private static final class SnapshotPayloadException extends RuntimeException {
        private SnapshotPayloadException(String message) {
            super(message);
        }
    }
}
