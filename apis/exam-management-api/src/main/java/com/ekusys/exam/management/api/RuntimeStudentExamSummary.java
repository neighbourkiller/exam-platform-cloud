package com.ekusys.exam.management.api;

import java.time.LocalDateTime;

public record RuntimeStudentExamSummary(Long examId, String name, LocalDateTime startTime,
                                        LocalDateTime endTime, int durationMinutes, String status,
                                        String proctoringLevel, String proctoringConfigJson) {
}
