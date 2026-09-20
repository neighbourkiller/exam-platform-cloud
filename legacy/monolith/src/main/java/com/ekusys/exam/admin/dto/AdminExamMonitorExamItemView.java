package com.ekusys.exam.admin.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminExamMonitorExamItemView {

    private Long examId;
    private String name;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer totalStudents;
    private Integer notStartedCount;
    private Integer answeringCount;
    private Integer submittedCount;
    private Integer abnormalCount;
    private Integer absentCount;
}
