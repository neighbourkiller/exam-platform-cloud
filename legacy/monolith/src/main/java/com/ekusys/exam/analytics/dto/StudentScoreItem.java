package com.ekusys.exam.analytics.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StudentScoreItem {

    private Long studentId;
    private String studentNo;
    private String username;
    private String studentName;
    private List<String> classNames;
    private Long submissionId;
    private String submissionStatus;
    private Integer objectiveScore;
    private Integer subjectiveScore;
    private Integer totalScore;
    private Boolean passFlag;
    private LocalDateTime submittedAt;
    private Boolean submitted;
}
