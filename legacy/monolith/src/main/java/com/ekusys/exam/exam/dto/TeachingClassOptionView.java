package com.ekusys.exam.exam.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TeachingClassOptionView {

    private Long id;
    private String name;
    private Long subjectId;
    private String subjectName;
    private Long teacherId;
    private String teacherName;
    private String term;
    private String status;
}

