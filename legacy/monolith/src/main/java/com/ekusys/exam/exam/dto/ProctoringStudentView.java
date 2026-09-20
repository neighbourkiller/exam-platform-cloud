package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProctoringStudentView {

    private Long studentId;
    private String studentName;
    private String username;
    private List<String> classNames;
    private Integer riskScore;
    private String riskLevel;
    private Integer eventCount;
    private String latestEventType;
    private LocalDateTime lastEventTime;
    private LocalDateTime lastSnapshotTime;
    private Boolean answering;
    private Boolean snapshotAlert;
    private Long totalOffscreenDurationMs;
    private Boolean longOffscreen;
    private ProctoringDispositionView disposition;
}
