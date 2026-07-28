package com.ekusys.exam.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.snapshot")
public class SnapshotProperties {
    private long flushIntervalMs = 900_000L;
    private long flushPollIntervalMs = 30_000L;
    private int flushMaxBatchesPerRun = 10;
    private long ttlHours = 48L;
    private int flushBatchSize = 100;
    private int flushWorkerCount = 4;
    private long flushLeaseMs = 60_000L;
    private long flushBackoffInitialMs = 5_000L;
    private double flushBackoffMultiplier = 2.0;
    private long flushBackoffMaxMs = 300_000L;
    private double flushBackoffJitter = 0.2;
    private int flushMaxAttempts = 12;
    private long flushReconcileIntervalMs = 300_000L;
    private int flushReconcileScanCount = 200;
    private long flushFailedRetentionHours = 168L;
    private long flushCleanupIntervalMs = 3_600_000L;
    private int flushCleanupBatchSize = 100;

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public long getFlushPollIntervalMs() {
        return flushPollIntervalMs;
    }

    public void setFlushPollIntervalMs(long flushPollIntervalMs) {
        this.flushPollIntervalMs = flushPollIntervalMs;
    }

    public int getFlushMaxBatchesPerRun() {
        return flushMaxBatchesPerRun;
    }

    public void setFlushMaxBatchesPerRun(int flushMaxBatchesPerRun) {
        this.flushMaxBatchesPerRun = flushMaxBatchesPerRun;
    }

    public long getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(long ttlHours) {
        this.ttlHours = ttlHours;
    }

    public int getFlushBatchSize() { return flushBatchSize; }
    public void setFlushBatchSize(int flushBatchSize) { this.flushBatchSize = flushBatchSize; }
    public int getFlushWorkerCount() { return flushWorkerCount; }
    public void setFlushWorkerCount(int flushWorkerCount) { this.flushWorkerCount = flushWorkerCount; }
    public long getFlushLeaseMs() { return flushLeaseMs; }
    public void setFlushLeaseMs(long flushLeaseMs) { this.flushLeaseMs = flushLeaseMs; }
    public long getFlushBackoffInitialMs() { return flushBackoffInitialMs; }
    public void setFlushBackoffInitialMs(long flushBackoffInitialMs) { this.flushBackoffInitialMs = flushBackoffInitialMs; }
    public double getFlushBackoffMultiplier() { return flushBackoffMultiplier; }
    public void setFlushBackoffMultiplier(double flushBackoffMultiplier) { this.flushBackoffMultiplier = flushBackoffMultiplier; }
    public long getFlushBackoffMaxMs() { return flushBackoffMaxMs; }
    public void setFlushBackoffMaxMs(long flushBackoffMaxMs) { this.flushBackoffMaxMs = flushBackoffMaxMs; }
    public double getFlushBackoffJitter() { return flushBackoffJitter; }
    public void setFlushBackoffJitter(double flushBackoffJitter) { this.flushBackoffJitter = flushBackoffJitter; }
    public int getFlushMaxAttempts() { return flushMaxAttempts; }
    public void setFlushMaxAttempts(int flushMaxAttempts) { this.flushMaxAttempts = flushMaxAttempts; }
    public long getFlushReconcileIntervalMs() { return flushReconcileIntervalMs; }
    public void setFlushReconcileIntervalMs(long flushReconcileIntervalMs) { this.flushReconcileIntervalMs = flushReconcileIntervalMs; }
    public int getFlushReconcileScanCount() { return flushReconcileScanCount; }
    public void setFlushReconcileScanCount(int flushReconcileScanCount) { this.flushReconcileScanCount = flushReconcileScanCount; }
    public long getFlushFailedRetentionHours() { return flushFailedRetentionHours; }
    public void setFlushFailedRetentionHours(long flushFailedRetentionHours) { this.flushFailedRetentionHours = flushFailedRetentionHours; }
    public long getFlushCleanupIntervalMs() { return flushCleanupIntervalMs; }
    public void setFlushCleanupIntervalMs(long flushCleanupIntervalMs) { this.flushCleanupIntervalMs = flushCleanupIntervalMs; }
    public int getFlushCleanupBatchSize() { return flushCleanupBatchSize; }
    public void setFlushCleanupBatchSize(int flushCleanupBatchSize) { this.flushCleanupBatchSize = flushCleanupBatchSize; }

    public long safeFlushIntervalMs() { return Math.max(1_000L, flushIntervalMs); }
    public long safeFlushPollIntervalMs() { return Math.max(1_000L, flushPollIntervalMs); }
    public int safeFlushMaxBatchesPerRun() { return Math.max(1, flushMaxBatchesPerRun); }
    public int safeFlushBatchSize() { return Math.max(1, flushBatchSize); }
    public int safeFlushWorkerCount() { return Math.max(1, flushWorkerCount); }
    public long safeFlushLeaseMs() { return Math.max(1_000L, flushLeaseMs); }
    public long safeFlushBackoffInitialMs() { return Math.max(1L, flushBackoffInitialMs); }
    public long safeFlushBackoffMaxMs() { return Math.max(safeFlushBackoffInitialMs(), flushBackoffMaxMs); }
    public int safeFlushMaxAttempts() { return Math.max(1, flushMaxAttempts); }
    public int safeFlushReconcileScanCount() { return Math.max(1, flushReconcileScanCount); }
    public long safeFlushFailedRetentionMs() { return Math.max(1L, flushFailedRetentionHours) * 3_600_000L; }
    public int safeFlushCleanupBatchSize() { return Math.max(1, flushCleanupBatchSize); }
}
