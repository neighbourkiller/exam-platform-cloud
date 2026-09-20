package com.ekusys.exam.exam.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SubmitResultView {

    private Long submissionId;
    private Integer objectiveScore;
    private Integer subjectiveScore;
    private Integer totalScore;
    private Boolean passFlag;
    private String status;
}
