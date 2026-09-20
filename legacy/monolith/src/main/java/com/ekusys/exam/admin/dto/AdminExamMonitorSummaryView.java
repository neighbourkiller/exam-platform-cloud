package com.ekusys.exam.admin.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminExamMonitorSummaryView {

    private Integer totalExams;
    private Integer notStartedCount;
    private Integer answeringCount;
    private Integer submittedCount;
    private Integer abnormalCount;
    private Integer absentCount;
    private List<AdminExamMonitorExamItemView> exams;
}
