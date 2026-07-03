package com.ekusys.exam.admin.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CourseView {

    private Long id;
    private String name;
    private String description;
}