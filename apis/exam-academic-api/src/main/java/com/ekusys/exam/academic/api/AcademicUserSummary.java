package com.ekusys.exam.academic.api;

import java.util.List;

public record AcademicUserSummary(Long userId, String studentNo, String enrollmentYear,
                                  List<TeachingClassSummary> teachingClasses) {
}
