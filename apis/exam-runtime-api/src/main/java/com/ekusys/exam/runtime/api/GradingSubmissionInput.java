package com.ekusys.exam.runtime.api;

import java.time.LocalDateTime;
import java.util.List;

public record GradingSubmissionInput(Long submissionId, Long examId, Long studentId,
                                     String examName, Integer passScore, Long paperSnapshotId, LocalDateTime submittedAt,
                                     List<GradingAnswerInput> answers) {
}
