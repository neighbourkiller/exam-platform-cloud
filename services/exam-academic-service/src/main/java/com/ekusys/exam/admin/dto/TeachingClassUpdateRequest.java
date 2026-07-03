package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TeachingClassUpdateRequest {

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

