package com.ekusys.exam.exam.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class ExamCreateRequest {

    @NotBlank
    private String name;

    @NotNull
    private Long paperId;

    @NotNull
    private LocalDateTime startTime;

    @NotNull
    @Future
    private LocalDateTime endTime;

    @NotNull
    @Min(1)
    private Integer durationMinutes;

    @NotNull
    @Min(0)
    private Integer passScore;

    @NotEmpty
    private List<Long> targetClassIds;

    private String proctoringLevel;

    private ProctoringPolicyView proctoringPolicy;
}
