package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;

public record EntryActivateView(
    Long examId,
    String status,
    boolean resumed,
    LocalDateTime serverTime,
    LocalDateTime startTime,
    LocalDateTime endTime,
    LocalDateTime deadlineTime,
    String leaseToken,
    LocalDateTime leaseExpiresAt,
    Integer heartbeatIntervalSeconds,
    Integer leaseTimeoutSeconds,
    long paperSnapshotVersion
) {
}
