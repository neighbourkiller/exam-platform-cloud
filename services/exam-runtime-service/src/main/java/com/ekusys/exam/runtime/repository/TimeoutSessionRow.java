package com.ekusys.exam.runtime.repository;

import java.time.LocalDateTime;

public record TimeoutSessionRow(Long id, Long examId, Long studentId, LocalDateTime deadlineTime) {
}
