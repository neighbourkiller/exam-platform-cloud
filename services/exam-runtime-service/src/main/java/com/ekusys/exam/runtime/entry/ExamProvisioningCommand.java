package com.ekusys.exam.runtime.entry;

import java.time.LocalDateTime;
import java.util.List;

public record ExamProvisioningCommand(
    String eventId,
    int eventVersion,
    Long examId,
    String name,
    LocalDateTime startTime,
    LocalDateTime endTime,
    int durationMinutes,
    int passScore,
    Long paperSnapshotId,
    long paperSnapshotVersion,
    Long publisherId,
    String proctoringLevel,
    String proctoringConfigJson,
    String examStatus,
    List<Long> candidateIds
) {
}
