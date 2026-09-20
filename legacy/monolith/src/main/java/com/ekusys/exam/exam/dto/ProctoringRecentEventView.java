package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProctoringRecentEventView {

    private Long studentId;
    private String studentName;
    private String username;
    private List<String> classNames;
    private String eventType;
    private LocalDateTime eventTime;
    private Long durationMs;
}
