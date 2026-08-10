package com.ekusys.exam.runtime.entry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class ExamEntryMetrics {
    private final MeterRegistry registry;
    private final AtomicLong provisioning = new AtomicLong();
    private final AtomicLong pendingCandidates = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong lifecycleDlq = new AtomicLong();
    private final AtomicLong outboxPending = new AtomicLong();

    public ExamEntryMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("exam.entry.provisioning.current", provisioning, AtomicLong::get).register(registry);
        Gauge.builder("exam.entry.provisioning.pending.candidates", pendingCandidates, AtomicLong::get)
            .register(registry);
        Gauge.builder("exam.entry.provisioning.failed", failed, AtomicLong::get).register(registry);
        Gauge.builder("exam.entry.lifecycle.dlq", lifecycleDlq, AtomicLong::get).register(registry);
        Gauge.builder("exam.entry.outbox.pending", outboxPending, AtomicLong::get).register(registry);
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void finish(Timer.Sample sample, String endpoint, String status, String code) {
        sample.stop(Timer.builder("exam.entry.request.duration")
            .tag("endpoint", endpoint)
            .tag("status", status)
            .tag("code", code == null ? "NONE" : code)
            .publishPercentileHistogram()
            .register(registry));
    }

    public void slot(int slot) {
        Counter.builder("exam.entry.slot.assigned")
            .tag("slot", String.valueOf(slot))
            .register(registry)
            .increment();
    }

    public void activationTransaction(long nanos, String outcome) {
        Timer.builder("exam.entry.activation.transaction.duration")
            .tag("outcome", outcome)
            .publishPercentileHistogram()
            .register(registry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void provisioningStarted(long candidates) {
        provisioning.incrementAndGet();
        pendingCandidates.set(Math.max(0L, candidates));
    }

    public void provisioningProgress(long remaining) {
        pendingCandidates.set(Math.max(0L, remaining));
    }

    public void provisioningFinished() {
        provisioning.updateAndGet(value -> Math.max(0L, value - 1L));
        pendingCandidates.set(0L);
    }

    public void lifecycleDeadLettered() {
        lifecycleDlq.incrementAndGet();
    }

    public void operationalSnapshot(long provisioningCount, long pendingCandidateCount,
                                    long failedCount, long pendingOutboxCount) {
        provisioning.set(Math.max(0L, provisioningCount));
        pendingCandidates.set(Math.max(0L, pendingCandidateCount));
        failed.set(Math.max(0L, failedCount));
        outboxPending.set(Math.max(0L, pendingOutboxCount));
    }
}
