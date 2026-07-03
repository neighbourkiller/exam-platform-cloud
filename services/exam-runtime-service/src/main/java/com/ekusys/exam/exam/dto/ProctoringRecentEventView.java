package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ProctoringRecentEventView(Long studentId, String studentName, String username,
                                        List<String> classNames, String eventType,
                                        LocalDateTime eventTime, Long durationMs) {
}
