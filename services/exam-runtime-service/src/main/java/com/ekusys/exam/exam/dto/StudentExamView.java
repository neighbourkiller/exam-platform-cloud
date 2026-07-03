package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StudentExamView {

    private Long examId;
    private String name;
    private String subjectName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private String status;
    private Boolean submitted;
    private String proctoringLevel;
    private ProctoringPolicyView proctoringPolicy;
}
