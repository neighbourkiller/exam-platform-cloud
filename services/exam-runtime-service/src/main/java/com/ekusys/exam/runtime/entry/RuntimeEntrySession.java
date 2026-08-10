package com.ekusys.exam.runtime.entry;

import java.time.LocalDateTime;

public record RuntimeEntrySession(
    Long id,
    Long examId,
    Long studentId,
    String status,
    LocalDateTime startTime,
    LocalDateTime deadlineTime,
    String activeClientId,
    String activeClientToken,
    LocalDateTime activeClientLeaseUntil,
    Long submissionId,
    long draftVersion
) {
}
