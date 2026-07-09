package com.ekusys.exam.grading.messaging;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.grading.service.GradingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class SubmissionAcceptedConsumerTest {

    private static final String PAYLOAD = """
        {"eventId":"evt-1","data":{"submissionId":99}}
        """;

    @Mock
    private GradingService service;

    @Mock
    private JdbcTemplate jdbc;

    private SubmissionAcceptedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SubmissionAcceptedConsumer(service, new ObjectMapper(), jdbc);
    }

    @Test
    void skipsAlreadyProcessedEvent() throws Exception {
        when(jdbc.update(anyString(), eq("evt-1"), eq("grading-submission-accepted"))).thenReturn(0);

        consumer.consume(PAYLOAD);

        verify(service, never()).processSubmission(99L);
    }

    @Test
    void propagatesProcessingFailureForRetryAndDeadLetter() {
        IllegalStateException failure = new IllegalStateException("boom");
        when(jdbc.update(anyString(), eq("evt-1"), eq("grading-submission-accepted"))).thenReturn(1);
        doThrow(failure).when(service).processSubmission(99L);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> consumer.consume(PAYLOAD));

        assertSame(failure, thrown);
        verify(service).processSubmission(99L);
    }
}
