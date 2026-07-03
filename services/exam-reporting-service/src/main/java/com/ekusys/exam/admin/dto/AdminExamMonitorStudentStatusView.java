package com.ekusys.exam.admin.dto;

import java.time.LocalDateTime;

public record AdminExamMonitorStudentStatusView(Long studentId, String username, String realName, String className,
                                                String status, Boolean abnormal, LocalDateTime submittedAt,
                                                LocalDateTime lastEventTime, String latestEventType) {
}
