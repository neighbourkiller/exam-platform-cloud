package com.ekusys.exam.grading.api;

import java.util.List;

public record GradeProjectionProgress(List<Long> synchronizedSubmissionIds) {}
