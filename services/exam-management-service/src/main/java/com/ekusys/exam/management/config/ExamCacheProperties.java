package com.ekusys.exam.management.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.exam-cache")
public class ExamCacheProperties {
    private boolean enabled = true;
    private long ttlHours = 48;
    private long prewarmAheadMinutes = 10;
    private long prewarmScanDelayMillis = 60_000;
    private int prewarmBatchSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(long ttlHours) {
        this.ttlHours = ttlHours;
    }

    public long getPrewarmAheadMinutes() {
        return prewarmAheadMinutes;
    }

    public void setPrewarmAheadMinutes(long prewarmAheadMinutes) {
        this.prewarmAheadMinutes = prewarmAheadMinutes;
    }

    public long getPrewarmScanDelayMillis() {
        return prewarmScanDelayMillis;
    }

    public void setPrewarmScanDelayMillis(long prewarmScanDelayMillis) {
        this.prewarmScanDelayMillis = prewarmScanDelayMillis;
    }

    public int getPrewarmBatchSize() {
        return prewarmBatchSize;
    }

    public void setPrewarmBatchSize(int prewarmBatchSize) {
        this.prewarmBatchSize = prewarmBatchSize;
    }

    public long safeTtlHours() {
        return Math.max(1, ttlHours);
    }

    public long safePrewarmAheadMinutes() {
        return Math.max(1, prewarmAheadMinutes);
    }

    public int safePrewarmBatchSize() {
        return Math.max(1, prewarmBatchSize);
    }
}
