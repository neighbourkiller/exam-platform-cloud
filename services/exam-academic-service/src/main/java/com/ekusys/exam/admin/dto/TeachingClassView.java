package com.ekusys.exam.admin.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TeachingClassView {

    private Long id;
    private String name;
    private Long subjectId;
    private String subjectName;
    private Long teacherId;
    private String teacherName;
    private String term;
    private String status;
    private Integer capacity;
}

