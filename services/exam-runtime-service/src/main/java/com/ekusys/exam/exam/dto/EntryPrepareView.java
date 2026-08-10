package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;

public record EntryPrepareView(
    Long examId,
    String entryToken,
    LocalDateTime serverTime,
    LocalDateTime scheduledActivationTime,
    int slot,
    String status
) {
}
