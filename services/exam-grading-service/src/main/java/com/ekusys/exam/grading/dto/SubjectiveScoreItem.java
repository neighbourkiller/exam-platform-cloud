package com.ekusys.exam.grading.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SubjectiveScoreItem {

    @NotNull
    private Long submissionAnswerId;

    @NotNull
    @Min(0)
    private Integer score;

    private String comment;

    @NotBlank
    private String leaseToken;
}
