package com.ekusys.exam.iam.admin.dto;

public record TeachingClassView(Long id, String name, Long subjectId, String subjectName, Long teacherId,
                                String teacherName, String term, String status, Integer capacity) {
}
