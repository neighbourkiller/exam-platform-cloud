package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;

public record ProctoringTimelineEventView(String eventType, LocalDateTime eventTime, Long durationMs,
                                          String payload, String evidenceJson) {
}
