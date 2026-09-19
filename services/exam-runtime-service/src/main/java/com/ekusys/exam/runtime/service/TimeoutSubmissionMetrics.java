package com.ekusys.exam.runtime.service;

import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TimeoutSubmissionBacklog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class TimeoutSubmissionMetrics {
    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong processing = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestOverdueMs = new AtomicLong();
    private final AtomicLong reconcileLastTriggerEpochMs = new AtomicLong();
    private final AtomicLong reconcileLastSuccessEpochMs = new AtomicLong();
    private final AtomicLong reconcileOverdue = new AtomicLong();

    public TimeoutSubmissionMetrics(MeterRegistry registry,
                                    @Qualifier("timeoutSubmissionExecutor") ThreadPoolTaskExecutor executor,
                                    @Nullable @Qualifier("finalizationPostCommitExecutor") ThreadPoolTaskExecutor postCommitExecutor) {
        this.registry = registry;
        backlogGauge("PENDING", pending);
        backlogGauge("PROCESSING", processing);
        backlogGauge("FAILED", failed);
        Gauge.builder("exam.timeout.submission.oldest.overdue", oldestOverdueMs, AtomicLong::get)
            .baseUnit("milliseconds")
            .register(registry);
        Gauge.builder("exam.timeout.submission.reconcile.last.trigger", reconcileLastTriggerEpochMs, AtomicLong::get)
            .baseUnit("milliseconds")
            .register(registry);
        Gauge.builder("exam.timeout.submission.reconcile.last.success", reconcileLastSuccessEpochMs, AtomicLong::get)
            .baseUnit("milliseconds")
            .register(registry);
        Gauge.builder("exam.timeout.submission.reconcile.overdue", reconcileOverdue, AtomicLong::get)
            .register(registry);
        Gauge.builder("exam.timeout.submission.worker.active", executor, ThreadPoolTaskExecutor::getActiveCount)
            .register(registry);
        Gauge.builder("exam.timeout.submission.worker.queue", executor, TimeoutSubmissionMetrics::queueSize)
            .register(registry);
        if (postCommitExecutor != null) {
            Gauge.builder("exam.timeout.submission.post_commit.active", postCommitExecutor, ThreadPoolTaskExecutor::getActiveCount)
                .register(registry);
            Gauge.builder("exam.timeout.submission.post_commit.queue", postCommitExecutor, TimeoutSubmissionMetrics::queueSize)
                .register(registry);
        }
    }

    public void increment(String outcome) {
        registry.counter("exam.timeout.submission.events", "outcome", outcome).increment();
    }

    public void increment(String outcome, long amount) {
        if (amount > 0) {
            registry.counter("exam.timeout.submission.events", "outcome", outcome).increment(amount);
        }
    }

    public void recordFinalization(Duration duration, String outcome) {
        registry.timer("exam.timeout.submission.finalization", "outcome", outcome).record(duration);
    }

    public void recordCompletionLatency(LocalDateTime dueAt, LocalDateTime completedAt) {
        if (dueAt == null || completedAt == null || completedAt.isBefore(dueAt)) {
            return;
        }
        registry.timer("exam.timeout.submission.completion.latency")
            .record(Duration.between(dueAt, completedAt));
    }

    public void recordReconcileTriggered() {
        reconcileLastTriggerEpochMs.set(System.currentTimeMillis());
        increment("reconcile_triggered");
    }

    public void recordReconcileCompleted() {
        reconcileLastSuccessEpochMs.set(System.currentTimeMillis());
        increment("reconcile_completed");
    }

    public void recordReconcileOutcome(String outcome) {
        increment("reconcile_" + outcome);
    }

    public void updateBacklog(TimeoutSubmissionBacklog backlog) {
        if (backlog == null) {
            return;
        }
        pending.set(backlog.pending());
        processing.set(backlog.processing());
        failed.set(backlog.failed());
        oldestOverdueMs.set(backlog.oldestOverdueMs());
        reconcileOverdue.set(backlog.overdue());
    }

    public void recordProjection(String outcome) {
        registry.counter("exam.submission.projection.events", "outcome", outcome).increment();
    }

    public void recordStage(String stage, Duration duration) {
        if (duration != null && !duration.isNegative()) {
            registry.timer("exam.timeout.submission.stage", "stage", stage).record(duration);
        }
    }

    private void backlogGauge(String state, AtomicLong value) {
        Gauge.builder("exam.timeout.submission.backlog", value, AtomicLong::get)
            .tag("state", state)
            .register(registry);
    }

    private static int queueSize(ThreadPoolTaskExecutor executor) {
        return executor.getThreadPoolExecutor().getQueue().size();
    }
}
