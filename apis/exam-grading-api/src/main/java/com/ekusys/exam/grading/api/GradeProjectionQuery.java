package com.ekusys.exam.grading.api;

import java.util.List;

public record GradeProjectionQuery(Long examId, List<Revision> revisions) {
    public record Revision(Long submissionId, long gradeRevision) {}
}
