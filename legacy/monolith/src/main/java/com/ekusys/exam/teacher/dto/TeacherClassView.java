package com.ekusys.exam.teacher.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TeacherClassView {

    private Long id;
    private String name;
    private Long subjectId;
    private String subjectName;
    private String term;
    private String status;
    private Integer capacity;
    private Long studentCount;
}

