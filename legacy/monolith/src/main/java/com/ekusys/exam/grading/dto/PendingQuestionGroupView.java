package com.ekusys.exam.grading.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PendingQuestionGroupView {

    private Long examId;
    private String examName;
    private Long questionId;
    private String questionContent;
    private String referenceAnswer;
    private String analysis;
    private Integer defaultScore;
    private Integer sortOrder;
    private Integer pendingCount;
}
