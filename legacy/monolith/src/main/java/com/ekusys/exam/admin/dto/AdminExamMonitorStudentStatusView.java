package com.ekusys.exam.admin.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminExamMonitorStudentStatusView {

    private Long studentId;
    private String username;
    private String realName;
    private String className;
    private String status;
    private Boolean abnormal;
    private LocalDateTime submittedAt;
    private LocalDateTime lastEventTime;
    private String latestEventType;
}
