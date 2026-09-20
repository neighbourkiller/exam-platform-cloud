package com.ekusys.exam.exam.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StudentExamResultView {

    private Long examId;
    private String name;
    private String subjectName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private String examStatus;

    private Long submissionId;
    private String submissionStatus;
    private Integer objectiveScore;
    private Integer subjectiveScore;
    private Integer totalScore;
    private Boolean passFlag;
    private LocalDateTime submittedAt;

    private Boolean submitted;
}
