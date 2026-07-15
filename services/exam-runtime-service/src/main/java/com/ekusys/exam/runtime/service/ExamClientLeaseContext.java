package com.ekusys.exam.runtime.service;

import com.ekusys.exam.exam.dto.ExamClientLeaseView;
import java.time.LocalDateTime;

record ExamClientLeaseContext(Long sessionId, LocalDateTime deadline, LocalDateTime receivedAt,
                              ExamClientLeaseView lease) {
}
