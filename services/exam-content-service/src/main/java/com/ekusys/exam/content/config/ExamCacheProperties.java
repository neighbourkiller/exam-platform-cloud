package com.ekusys.exam.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.exam-cache")
public class ExamCacheProperties {
    private boolean enabled = true;
    private long ttlHours = 48;
    private long lockTtlMillis = 5_000;
    private long lockWaitMillis = 500;
    private long lockPollMillis = 50;

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

    public long getLockTtlMillis() {
        return lockTtlMillis;
    }

    public void setLockTtlMillis(long lockTtlMillis) {
        this.lockTtlMillis = lockTtlMillis;
    }

    public long getLockWaitMillis() {
        return lockWaitMillis;
    }

    public void setLockWaitMillis(long lockWaitMillis) {
        this.lockWaitMillis = lockWaitMillis;
    }

    public long getLockPollMillis() {
        return lockPollMillis;
    }

    public void setLockPollMillis(long lockPollMillis) {
        this.lockPollMillis = lockPollMillis;
    }

    public long safeTtlHours() {
        return Math.max(1, ttlHours);
    }

    public long safeLockTtlMillis() {
        return Math.max(1_000, lockTtlMillis);
    }

    public long safeLockWaitMillis() {
        return Math.max(0, lockWaitMillis);
    }

    public long safeLockPollMillis() {
        return Math.max(10, lockPollMillis);
    }
}
