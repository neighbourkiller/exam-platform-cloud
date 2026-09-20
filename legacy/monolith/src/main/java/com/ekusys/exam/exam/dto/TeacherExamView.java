package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TeacherExamView {

    private Long examId;
    private String name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Integer passScore;
    private String status;
    private String proctoringLevel;
    private ProctoringPolicyView proctoringPolicy;
}
