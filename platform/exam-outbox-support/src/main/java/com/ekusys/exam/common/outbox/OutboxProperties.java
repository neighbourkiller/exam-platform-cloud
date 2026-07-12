package com.ekusys.exam.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {
    private boolean enabled = true;
    private String exchange = "exam.events";
    private long publishDelayMs = 1_000;
    private int batchSize = 20;
    private int workerCount = 4;
    private long leaseDurationMs = 60_000;
    private long confirmTimeoutMs = 3_000;
    private int maxAttempts = 12;
    private long backoffInitialMs = 5_000;
    private double backoffMultiplier = 2.0;
    private long backoffMaxMs = 300_000;
    private double backoffJitter = 0.2;
    private long cleanupDelayMs = 3_600_000;
    private long publishedRetentionMs = 604_800_000;
    private int cleanupBatchSize = 500;
    private int cleanupMaxBatches = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public long getPublishDelayMs() { return publishDelayMs; }
    public void setPublishDelayMs(long publishDelayMs) { this.publishDelayMs = publishDelayMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getWorkerCount() { return workerCount; }
    public void setWorkerCount(int workerCount) { this.workerCount = workerCount; }
    public long getLeaseDurationMs() { return leaseDurationMs; }
    public void setLeaseDurationMs(long leaseDurationMs) { this.leaseDurationMs = leaseDurationMs; }
    public long getConfirmTimeoutMs() { return confirmTimeoutMs; }
    public void setConfirmTimeoutMs(long confirmTimeoutMs) { this.confirmTimeoutMs = confirmTimeoutMs; }
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
    public long getCleanupDelayMs() { return cleanupDelayMs; }
    public void setCleanupDelayMs(long cleanupDelayMs) { this.cleanupDelayMs = cleanupDelayMs; }
    public long getPublishedRetentionMs() { return publishedRetentionMs; }
    public void setPublishedRetentionMs(long publishedRetentionMs) { this.publishedRetentionMs = publishedRetentionMs; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }
    public int getCleanupMaxBatches() { return cleanupMaxBatches; }
    public void setCleanupMaxBatches(int cleanupMaxBatches) { this.cleanupMaxBatches = cleanupMaxBatches; }

    int safeBatchSize() { return Math.max(1, batchSize); }
    int safeWorkerCount() { return Math.max(1, workerCount); }
    long safeLeaseDurationMs() { return Math.max(1_000, leaseDurationMs); }
    long safeConfirmTimeoutMs() { return Math.max(100, confirmTimeoutMs); }
    int safeMaxAttempts() { return Math.max(1, maxAttempts); }
    int safeCleanupBatchSize() { return Math.max(1, cleanupBatchSize); }
    int safeCleanupMaxBatches() { return Math.max(1, cleanupMaxBatches); }
}
