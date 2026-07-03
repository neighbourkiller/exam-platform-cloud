package com.ekusys.exam.academic.api;

public record TeachingClassSummary(Long id, String name, Long subjectId, String subjectName,
                                   Long teacherId, String term, String status, Integer capacity) {
}
