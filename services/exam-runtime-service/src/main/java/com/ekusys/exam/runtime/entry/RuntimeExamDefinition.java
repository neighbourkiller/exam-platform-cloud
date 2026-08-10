package com.ekusys.exam.runtime.entry;

import java.time.LocalDateTime;

public record RuntimeExamDefinition(
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
    String provisioningStatus,
    int candidateCount,
    int preparedCount
) {
    public boolean ready() {
        return "READY".equals(provisioningStatus);
    }

    public boolean terminated() {
        return "TERMINATED".equals(provisioningStatus) || "TERMINATED".equals(examStatus);
    }
}
