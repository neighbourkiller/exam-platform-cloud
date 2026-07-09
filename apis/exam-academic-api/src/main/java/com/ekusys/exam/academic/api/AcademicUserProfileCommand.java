package com.ekusys.exam.academic.api;

import java.util.List;

public record AcademicUserProfileCommand(List<String> roleCodes, String studentNo, String enrollmentYear,
                                         List<Long> teachingClassIds, String teacherNo, String title) {
}
