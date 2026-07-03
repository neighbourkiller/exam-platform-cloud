package com.ekusys.exam.management.api;

import com.ekusys.exam.content.api.PaperSnapshotView;
import java.time.LocalDateTime;
import java.util.List;

public record RuntimeExamSnapshot(Long examId, String name, LocalDateTime startTime,
                                  LocalDateTime endTime, int durationMinutes, int passScore,
                                  String status, Long publisherId, String proctoringLevel, String proctoringConfigJson,
                                  List<Long> candidateIds, PaperSnapshotView paper) {
}
