package com.ekusys.exam.grading.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PendingQuestionAnswerView {

    private Long submissionId;
    private Long submissionAnswerId;
    private Long studentId;
    private String studentName;
    private String answerText;
    private LocalDateTime submittedAt;
}
