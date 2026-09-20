package com.ekusys.exam.teacher.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class TeacherClassAddStudentsRequest {

    @NotEmpty
    private List<Long> studentIds;
}

