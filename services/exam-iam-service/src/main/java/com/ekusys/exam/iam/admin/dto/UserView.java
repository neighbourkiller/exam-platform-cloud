package com.ekusys.exam.iam.admin.dto;

import java.util.List;

public record UserView(Long id, String username, String realName, Boolean enabled, String studentNo,
                       String enrollmentYear, List<TeachingClassView> teachingClasses, List<RoleView> roles) {
}
