package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SubmissionStatusView {
    private Long submissionId;
    private String sessionStatus;
    private String submissionStatus;
    private String timeoutTaskStatus;
    private Boolean timeoutSubmit;
    private LocalDateTime submittedAt;
}
