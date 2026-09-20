package com.ekusys.exam.exam.service;

import java.time.LocalDateTime;

record ExamSubmissionAcceptedContext(Long submissionId,
                                     Long examId,
                                     Long studentId,
                                     LocalDateTime submittedAt,
                                     boolean timeoutSubmit) {
}
