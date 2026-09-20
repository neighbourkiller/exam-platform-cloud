package com.ekusys.exam.analytics.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExamOverviewItem {

    private Integer totalStudents;
    private Integer passCount;
    private Double passRate;
    private Double avgScore;
    private Integer maxScore;
    private Integer minScore;
}
