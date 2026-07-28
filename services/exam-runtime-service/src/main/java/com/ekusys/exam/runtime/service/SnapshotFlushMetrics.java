package com.ekusys.exam.runtime.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class SnapshotFlushMetrics {
    private final MeterRegistry registry;
    private final AtomicLong dirty = new AtomicLong();
    private final AtomicLong processing = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestDirtyOverdueMs = new AtomicLong();

    public SnapshotFlushMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerGauge("dirty", dirty);
        registerGauge("processing", processing);
        registerGauge("failed", failed);
        Gauge.builder("exam.snapshot.flush.oldest.dirty.overdue", oldestDirtyOverdueMs, AtomicLong::get)
            .baseUnit("milliseconds")
            .register(registry);
    }

    public void increment(String outcome) {
        registry.counter("exam.snapshot.flush.events", "outcome", outcome).increment();
    }

    public void increment(String outcome, long amount) {
        if (amount > 0) {
            registry.counter("exam.snapshot.flush.events", "outcome", outcome).increment(amount);
        }
    }

    public void updateBacklog(SnapshotFlushBacklog backlog) {
        dirty.set(backlog.dirty());
        processing.set(backlog.processing());
        failed.set(backlog.failed());
        oldestDirtyOverdueMs.set(backlog.oldestDirtyOverdueMs());
    }

    private void registerGauge(String state, AtomicLong value) {
        Gauge.builder("exam.snapshot.flush.backlog", value, AtomicLong::get)
            .tag("state", state)
            .register(registry);
    }
}
