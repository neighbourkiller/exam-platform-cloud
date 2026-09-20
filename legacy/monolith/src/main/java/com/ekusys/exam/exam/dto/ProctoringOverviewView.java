package com.ekusys.exam.exam.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProctoringOverviewView {

    private Long examId;
    private String examName;
    private String examStatus;
    private Integer totalStudents;
    private Integer answeringStudents;
    private Integer lowRiskCount;
    private Integer mediumRiskCount;
    private Integer highRiskCount;
    private Integer snapshotAlertCount;
    private Integer pendingReviewDispositionCount;
    private Integer confirmedDispositionCount;
    private Integer falsePositiveDispositionCount;
    private Integer closedDispositionCount;
    private List<ProctoringRecentEventView> recentEvents;
    private List<ProctoringEventStatView> eventTypeStats;
}
