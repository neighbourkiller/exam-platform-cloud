package com.ekusys.exam.teacher.dto;

import lombok.Data;

@Data
public class TeacherClassStudentCandidateQueryRequest {

    private long pageNum = 1;
    private long pageSize = 10;
    private String keyword;
}

