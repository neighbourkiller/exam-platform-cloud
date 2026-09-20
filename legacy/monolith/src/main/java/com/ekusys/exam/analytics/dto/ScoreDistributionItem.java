package com.ekusys.exam.analytics.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScoreDistributionItem {

    private String range;
    private Integer count;
}
