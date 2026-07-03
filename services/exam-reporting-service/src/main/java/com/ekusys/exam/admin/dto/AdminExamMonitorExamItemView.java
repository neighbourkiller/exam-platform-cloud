package com.ekusys.exam.admin.dto;

import java.time.LocalDateTime;

public record AdminExamMonitorExamItemView(Long examId, String name, String status, LocalDateTime startTime,
                                           LocalDateTime endTime, Integer totalStudents, Integer notStartedCount,
                                           Integer answeringCount, Integer submittedCount, Integer abnormalCount,
                                           Integer absentCount) {
}
