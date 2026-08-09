package com.ekusys.exam.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.timeout-submission")
public class TimeoutSubmissionProperties {
    private boolean enabled;
    private int batchSize = 200;
    private int workerCount = 8;
    private long leaseMs = 60_000L;
    private long maxRunMs = 25_000L;
    private int maxAttempts = 12;
    private long backoffInitialMs = 1_000L;
    private double backoffMultiplier = 2.0;
    private long backoffMaxMs = 30_000L;
    private double backoffJitter = 0.2;
    private int reconcileBatchSize = 1_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getWorkerCount() { return workerCount; }
    public void setWorkerCount(int workerCount) { this.workerCount = workerCount; }
    public long getLeaseMs() { return leaseMs; }
    public void setLeaseMs(long leaseMs) { this.leaseMs = leaseMs; }
    public long getMaxRunMs() { return maxRunMs; }
    public void setMaxRunMs(long maxRunMs) { this.maxRunMs = maxRunMs; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getBackoffInitialMs() { return backoffInitialMs; }
    public void setBackoffInitialMs(long backoffInitialMs) { this.backoffInitialMs = backoffInitialMs; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    public long getBackoffMaxMs() { return backoffMaxMs; }
    public void setBackoffMaxMs(long backoffMaxMs) { this.backoffMaxMs = backoffMaxMs; }
    public double getBackoffJitter() { return backoffJitter; }
    public void setBackoffJitter(double backoffJitter) { this.backoffJitter = backoffJitter; }
    public int getReconcileBatchSize() { return reconcileBatchSize; }
    public void setReconcileBatchSize(int reconcileBatchSize) { this.reconcileBatchSize = reconcileBatchSize; }

    public int safeBatchSize() { return Math.max(1, batchSize); }
    public int safeWorkerCount() { return Math.max(1, workerCount); }
    public long safeLeaseMs() { return Math.max(1_000L, leaseMs); }
    public long safeMaxRunMs() { return Math.max(1_000L, maxRunMs); }
    public int safeMaxAttempts() { return Math.max(1, maxAttempts); }
    public long safeBackoffInitialMs() { return Math.max(1L, backoffInitialMs); }
    public long safeBackoffMaxMs() { return Math.max(safeBackoffInitialMs(), backoffMaxMs); }
    public double safeBackoffMultiplier() { return Math.max(1.0, backoffMultiplier); }
    public double safeBackoffJitter() { return Math.min(1.0, Math.max(0.0, backoffJitter)); }
    public int safeReconcileBatchSize() { return Math.max(1, reconcileBatchSize); }
}
