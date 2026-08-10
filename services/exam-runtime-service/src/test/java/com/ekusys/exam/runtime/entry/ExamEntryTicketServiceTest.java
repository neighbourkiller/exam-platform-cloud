package com.ekusys.exam.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ExamEntryTicketServiceTest {
    @Test
    void sameExamAndStudentAlwaysUseSameSlot() {
        int first = ExamEntryTicketService.stableSlot(11L, 22L, 10);

        for (int index = 0; index < 100; index++) {
            assertThat(ExamEntryTicketService.stableSlot(11L, 22L, 10)).isEqualTo(first);
        }
    }

    @Test
    void tenThousandStudentsAreEvenlyDistributedAcrossTenSlots() {
        int[] counts = new int[10];
        for (long studentId = 1; studentId <= 10_000; studentId++) {
            counts[ExamEntryTicketService.stableSlot(99L, studentId, counts.length)]++;
        }

        assertThat(Arrays.stream(counts).min().orElseThrow()).isGreaterThanOrEqualTo(950);
        assertThat(Arrays.stream(counts).max().orElseThrow()).isLessThanOrEqualTo(1_050);
    }
}
