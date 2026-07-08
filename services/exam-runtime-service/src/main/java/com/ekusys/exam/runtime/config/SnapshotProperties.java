package com.ekusys.exam.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.snapshot")
public class SnapshotProperties {
    private long flushIntervalMs = 30_000L;
    private long ttlHours = 48L;

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public long getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(long ttlHours) {
        this.ttlHours = ttlHours;
    }
}
