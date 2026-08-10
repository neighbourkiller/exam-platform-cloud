package com.ekusys.exam.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.exam-entry")
public class ExamEntryProperties {
    private boolean enabled;
    private int waitingRoomMinutes = 10;
    private int slotCount = 10;
    private int ticketRetentionMinutes = 10;
    private int provisioningBatchSize = 500;
    private long l1MaximumMegabytes = 256;
    private long l1ExpireAfterAccessMinutes = 120;
    private long l2TtlHours = 48;
    private long cacheLoadWaitMs = 5_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWaitingRoomMinutes() {
        return waitingRoomMinutes;
    }

    public void setWaitingRoomMinutes(int waitingRoomMinutes) {
        this.waitingRoomMinutes = waitingRoomMinutes;
    }

    public int getSlotCount() {
        return slotCount;
    }

    public void setSlotCount(int slotCount) {
        this.slotCount = slotCount;
    }

    public int getTicketRetentionMinutes() {
        return ticketRetentionMinutes;
    }

    public void setTicketRetentionMinutes(int ticketRetentionMinutes) {
        this.ticketRetentionMinutes = ticketRetentionMinutes;
    }

    public int getProvisioningBatchSize() {
        return provisioningBatchSize;
    }

    public void setProvisioningBatchSize(int provisioningBatchSize) {
        this.provisioningBatchSize = provisioningBatchSize;
    }

    public long getL1MaximumMegabytes() {
        return l1MaximumMegabytes;
    }

    public void setL1MaximumMegabytes(long l1MaximumMegabytes) {
        this.l1MaximumMegabytes = l1MaximumMegabytes;
    }

    public long getL1ExpireAfterAccessMinutes() {
        return l1ExpireAfterAccessMinutes;
    }

    public void setL1ExpireAfterAccessMinutes(long l1ExpireAfterAccessMinutes) {
        this.l1ExpireAfterAccessMinutes = l1ExpireAfterAccessMinutes;
    }

    public long getL2TtlHours() {
        return l2TtlHours;
    }

    public void setL2TtlHours(long l2TtlHours) {
        this.l2TtlHours = l2TtlHours;
    }

    public long getCacheLoadWaitMs() {
        return cacheLoadWaitMs;
    }

    public void setCacheLoadWaitMs(long cacheLoadWaitMs) {
        this.cacheLoadWaitMs = cacheLoadWaitMs;
    }

    public int safeWaitingRoomMinutes() {
        return Math.max(1, waitingRoomMinutes);
    }

    public int safeSlotCount() {
        return Math.max(1, Math.min(60, slotCount));
    }

    public int safeTicketRetentionMinutes() {
        return Math.max(1, ticketRetentionMinutes);
    }

    public int safeProvisioningBatchSize() {
        return Math.max(1, Math.min(2_000, provisioningBatchSize));
    }

    public long safeL1MaximumBytes() {
        return Math.max(16L, l1MaximumMegabytes) * 1024L * 1024L;
    }

    public long safeL1ExpireAfterAccessMinutes() {
        return Math.max(1L, l1ExpireAfterAccessMinutes);
    }

    public long safeL2TtlHours() {
        return Math.max(1L, l2TtlHours);
    }

    public long safeCacheLoadWaitMs() {
        return Math.max(100L, Math.min(30_000L, cacheLoadWaitMs));
    }
}
