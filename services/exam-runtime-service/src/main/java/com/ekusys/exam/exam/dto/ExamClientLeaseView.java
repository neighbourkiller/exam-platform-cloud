package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExamClientLeaseView {

    private String leaseToken;

    private LocalDateTime leaseExpiresAt;

    private Integer heartbeatIntervalSeconds;

    private Integer leaseTimeoutSeconds;

    private Long serverEpochMs;

    private Long deadlineEpochMs;
}
