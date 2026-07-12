package com.ekusys.exam.common.outbox;

public record OutboxRow(
    String id,
    String eventType,
    String payload,
    int retryCount,
    String leaseToken
) {
}
