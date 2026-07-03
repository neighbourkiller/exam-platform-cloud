package com.ekusys.exam.teacher.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TeacherClassStudentView {

    private Long id;
    private String username;
    private String realName;
    private String studentNo;
}

