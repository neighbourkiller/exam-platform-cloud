package com.ekusys.exam.runtime.entry;

public record ExamProvisioningView(
    Long examId,
    String status,
    int candidateCount,
    int preparedCount
) {
    static ExamProvisioningView from(RuntimeExamDefinition definition) {
        return new ExamProvisioningView(
            definition.examId(), definition.provisioningStatus(),
            definition.candidateCount(), definition.preparedCount()
        );
    }
}
