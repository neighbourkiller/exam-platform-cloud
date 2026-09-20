package com.ekusys.exam.analytics.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WrongTopicItem {

    private Long questionId;
    private String questionContent;
    private Double wrongRate;
    private Integer wrongCount;
    private Integer totalCount;
}
