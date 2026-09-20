package com.ekusys.exam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;

@Data
public class UserUpdateRequest {

    @NotBlank
    private String realName;

    private Boolean enabled;
    private String studentNo;
    private String enrollmentYear;

    private List<Long> teachingClassIds;
}
