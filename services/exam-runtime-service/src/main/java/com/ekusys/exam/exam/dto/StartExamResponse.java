package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StartExamResponse {

    private Long examId;
    private String examName;
    private Boolean resumed;
    private Integer durationMinutes;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime deadlineTime;
    private Long serverEpochMs;
    private Long deadlineEpochMs;
    private LocalDateTime draftUpdatedAt;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private Integer heartbeatIntervalSeconds;
    private Integer leaseTimeoutSeconds;
    private ProctoringPolicyView proctoringPolicy;
    private List<StudentExamQuestionView> questions;
}
