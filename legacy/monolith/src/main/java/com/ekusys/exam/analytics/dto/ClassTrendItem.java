package com.ekusys.exam.analytics.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClassTrendItem {

    private Long classId;
    private String className;
    private Double avgScore;
}
