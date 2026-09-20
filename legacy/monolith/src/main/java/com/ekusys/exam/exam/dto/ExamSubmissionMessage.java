package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamSubmissionMessage {

    private Long submissionId;
    private Long examId;
    private Long studentId;
    private LocalDateTime submittedAt;
    private boolean timeoutSubmit;
}
