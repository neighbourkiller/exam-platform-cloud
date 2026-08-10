package com.ekusys.exam.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ExamActivationTimingTest {
    @Test
    void deadlineUsesActivationTimePlusDurationWhenItIsEarlier() {
        LocalDateTime activatedAt = LocalDateTime.of(2026, 8, 9, 10, 0);

        assertThat(ExamActivationTransactionService.calculateDeadline(
            activatedAt, 60, activatedAt.plusHours(2)
        )).isEqualTo(activatedAt.plusHours(1));
    }

    @Test
    void deadlineNeverExceedsExamEnd() {
        LocalDateTime activatedAt = LocalDateTime.of(2026, 8, 9, 11, 30);
        LocalDateTime examEnd = LocalDateTime.of(2026, 8, 9, 12, 0);

        assertThat(ExamActivationTransactionService.calculateDeadline(
            activatedAt, 60, examEnd
        )).isEqualTo(examEnd);
    }
}
