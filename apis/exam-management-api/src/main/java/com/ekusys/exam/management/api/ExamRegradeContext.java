package com.ekusys.exam.management.api;

import java.time.LocalDateTime;

public record ExamRegradeContext(Long examId, Long publisherId, Long paperSnapshotId,
                                 String status, LocalDateTime endTime) {}
