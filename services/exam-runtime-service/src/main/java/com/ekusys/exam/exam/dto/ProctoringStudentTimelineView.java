package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ProctoringStudentTimelineView(Long examId, String examName, String examStatus, Long studentId,
                                            String studentName, String username, List<String> classNames,
                                            Integer riskScore, String riskLevel, Integer eventCount,
                                            String latestEventType, LocalDateTime lastEventTime,
                                            LocalDateTime lastSnapshotTime, Boolean answering, Boolean snapshotAlert,
                                            Long totalOffscreenDurationMs, Boolean longOffscreen,
                                            ProctoringDispositionView disposition,
                                            List<ProctoringEventStatView> eventTypeStats,
                                            List<ProctoringTimelineEventView> events) {
}
