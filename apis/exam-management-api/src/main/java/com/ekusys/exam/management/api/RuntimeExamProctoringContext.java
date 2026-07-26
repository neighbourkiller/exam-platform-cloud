package com.ekusys.exam.management.api;

import java.util.List;

public record RuntimeExamProctoringContext(RuntimeExamMetadata metadata, String status,
                                           List<Long> candidateIds) {
}
