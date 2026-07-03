package com.ekusys.exam.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.asset-cleanup")
public class AssetCleanupProperties {

    private boolean enabled = true;
    private boolean startupRun = true;
    private long intervalMs = 600_000L;
    private long orphanGraceMinutes = 30L;
    private String minioPrefix = "question/";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isStartupRun() {
        return startupRun;
    }

    public void setStartupRun(boolean startupRun) {
        this.startupRun = startupRun;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getOrphanGraceMinutes() {
        return orphanGraceMinutes;
    }

    public void setOrphanGraceMinutes(long orphanGraceMinutes) {
        this.orphanGraceMinutes = orphanGraceMinutes;
    }

    public String getMinioPrefix() {
        return minioPrefix;
    }

    public void setMinioPrefix(String minioPrefix) {
        this.minioPrefix = minioPrefix;
    }
}
