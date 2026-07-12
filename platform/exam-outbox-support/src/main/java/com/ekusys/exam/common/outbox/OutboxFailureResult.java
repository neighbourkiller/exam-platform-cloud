package com.ekusys.exam.common.outbox;

public record OutboxFailureResult(boolean updated, boolean failedPermanently, int failureCount) {
    static OutboxFailureResult stale(int failureCount) {
        return new OutboxFailureResult(false, false, failureCount);
    }
}
