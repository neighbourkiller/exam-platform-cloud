package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CourseUpdateRequest {

    @NotBlank
    private String name;

    private String description;
}