package com.ekusys.exam.grading.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PendingAnswerView {

    private Long submissionId;
    private Long submissionAnswerId;
    private Long examId;
    private String examName;
    private Long studentId;
    private String studentName;
    private Long questionId;
    private String questionContent;
    private String answerText;
}
