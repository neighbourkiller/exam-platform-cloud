package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;

public record EntryActivateView(
    Long examId,
    String status,
    boolean resumed,
    LocalDateTime serverTime,
    Long serverEpochMs,
    LocalDateTime startTime,
    LocalDateTime endTime,
    LocalDateTime deadlineTime,
    Long deadlineEpochMs,
    String leaseToken,
    LocalDateTime leaseExpiresAt,
    Integer heartbeatIntervalSeconds,
    Integer leaseTimeoutSeconds,
    long paperSnapshotVersion
) {
}
