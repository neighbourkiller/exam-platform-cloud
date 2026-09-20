package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class CourseCreateRequest {

    @Positive
    private Long id;

    @NotBlank
    private String name;

    private String description;
}
