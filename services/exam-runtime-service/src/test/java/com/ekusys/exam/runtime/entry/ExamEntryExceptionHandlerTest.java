package com.ekusys.exam.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ExamEntryExceptionHandlerTest {
    private final ExamEntryExceptionHandler handler = new ExamEntryExceptionHandler();

    @Test
    void tooEarlyResponseContainsCodeRetryBodyAndRetryAfterHeader() {
        var response = handler.handle(new ExamEntryException(
            HttpStatus.TOO_EARLY, "EXAM_ENTRY_NOT_READY", "尚未到激活时刻", 1_250L
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_EARLY);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("EXAM_ENTRY_NOT_READY");
        assertThat(response.getBody().getData()).containsEntry("retryAfterMs", 1_250L);
    }

    @Test
    void preparingAndNotCandidateKeepRequiredHttpStatuses() {
        assertThat(handler.handle(new ExamEntryException(
            HttpStatus.SERVICE_UNAVAILABLE, "EXAM_PREPARING", "准备中", 500L
        )).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(handler.handle(new ExamEntryException(
            HttpStatus.FORBIDDEN, "EXAM_NOT_CANDIDATE", "非考生"
        )).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
