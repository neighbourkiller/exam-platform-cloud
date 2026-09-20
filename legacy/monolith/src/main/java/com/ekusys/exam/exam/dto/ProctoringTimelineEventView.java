package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProctoringTimelineEventView {

    private String eventType;
    private LocalDateTime eventTime;
    private Long durationMs;
    private String payload;
    private String evidenceJson;
}
