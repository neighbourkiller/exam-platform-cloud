package com.ekusys.exam.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class UserCreateRequest {
    @NotBlank private String username;
    @NotBlank private String realName;
    private String password;
    private String studentNo;
    private String enrollmentYear;
    private String teacherNo;
    private String title;
    @NotEmpty private List<Long> roleIds;
    private List<Long> teachingClassIds;
}
