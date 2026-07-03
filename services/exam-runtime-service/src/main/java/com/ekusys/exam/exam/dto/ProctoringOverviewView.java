package com.ekusys.exam.exam.dto;

import java.util.List;

public record ProctoringOverviewView(Long examId, String examName, String examStatus, Integer totalStudents,
                                     Integer answeringStudents, Integer lowRiskCount, Integer mediumRiskCount,
                                     Integer highRiskCount, Integer snapshotAlertCount,
                                     Integer pendingReviewDispositionCount, Integer confirmedDispositionCount,
                                     Integer falsePositiveDispositionCount, Integer closedDispositionCount,
                                     List<ProctoringRecentEventView> recentEvents,
                                     List<ProctoringEventStatView> eventTypeStats) {
}
