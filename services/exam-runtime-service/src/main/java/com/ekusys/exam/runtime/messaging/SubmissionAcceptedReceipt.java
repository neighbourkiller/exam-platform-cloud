package com.ekusys.exam.runtime.messaging;

import java.time.LocalDateTime;

public record SubmissionAcceptedReceipt(
    Long submissionId,
    Long examId,
    Long studentId,
    LocalDateTime submittedAt,
    LocalDateTime finalizedAt,
    Long finalSnapshotVersion,
    Boolean timeoutSubmit
) {
}
