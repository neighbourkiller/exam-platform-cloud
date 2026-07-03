package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class TeachingClassCreateRequest {

    @Positive
    private Long id;

    @NotBlank
    private String name;

    @NotNull
    private Long subjectId;

    @NotNull
    private Long teacherId;

    @NotBlank
    private String term;

    private String status;
    private Integer capacity;
}

