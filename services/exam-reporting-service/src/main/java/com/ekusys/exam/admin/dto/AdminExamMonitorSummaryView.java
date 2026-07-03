package com.ekusys.exam.admin.dto;

import java.util.List;

public record AdminExamMonitorSummaryView(Integer totalExams, Integer notStartedCount, Integer answeringCount,
                                          Integer submittedCount, Integer abnormalCount, Integer absentCount,
                                          List<AdminExamMonitorExamItemView> exams) {
}
