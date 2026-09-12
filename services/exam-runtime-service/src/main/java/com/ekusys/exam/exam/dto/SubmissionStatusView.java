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
public class SubmissionStatusView {
    private Long submissionId;
    private String sessionStatus;
    private String submissionStatus;
    private String timeoutTaskStatus;
    private Boolean timeoutSubmit;
    private LocalDateTime submittedAt;
    private String phase;
    private Boolean runtimeFinalized;
    private Long serverEpochMs;
    private String failureCode;
    private String incidentId;
    private Boolean retryable;
    private Long finalSnapshotVersion;
    private LocalDateTime finalizedAt;
}
