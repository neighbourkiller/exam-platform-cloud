package com.ekusys.exam.exam.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProctoringEventStatView {

    private String eventType;
    private Integer count;
    private Long totalDurationMs;
}
