package com.ekusys.exam.admin.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserView {

    private Long id;
    private String username;
    private String realName;
    private Boolean enabled;
    private String studentNo;
    private String enrollmentYear;
    private List<TeachingClassView> teachingClasses;
    private List<RoleView> roles;
}
