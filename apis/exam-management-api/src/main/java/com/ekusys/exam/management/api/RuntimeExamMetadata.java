package com.ekusys.exam.management.api;

import java.time.LocalDateTime;

public record RuntimeExamMetadata(Long examId, String name, LocalDateTime startTime,
                                  LocalDateTime endTime, int durationMinutes, int passScore,
                                  Long paperSnapshotId, long paperSnapshotVersion,
                                  Long publisherId, String proctoringLevel,
                                  String proctoringConfigJson) {
}
