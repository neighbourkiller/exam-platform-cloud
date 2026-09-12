package com.ekusys.exam.runtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.outbox.OutboxEventWriter;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RuntimeOutboxServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void sessionStartedUsesDeterministicEventIdPerSession() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        RuntimeOutboxService service = new RuntimeOutboxService(jdbc, writer);
        LocalDateTime eventTime = LocalDateTime.of(2026, 8, 9, 10, 0);

        service.sessionStarted(1_001L, 11L, 101L, eventTime);

        String eventId = service.sessionStartedEventId(1_001L);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(writer).append(
            eq(eventId), eq("EXAM_SESSION"), eq("1001"),
            eq("SessionStarted"), event.capture()
        );
        Map<String, Object> payload = (Map<String, Object>) event.getValue();
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertThat(payload.get("eventId")).isEqualTo(eventId);
        assertThat(payload.get("eventType")).isEqualTo("SessionStarted");
        assertThat(data.get("sessionId")).isEqualTo(1_001L);
        assertThat(data.get("eventTime")).isEqualTo(eventTime);
        assertThat(data.get("recordEvent")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void submissionEventKeepsExistingWireShape() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        ResultSet resultSet = mock(ResultSet.class);
        LocalDateTime submittedAt = LocalDateTime.of(2026, 7, 12, 10, 0);
        when(resultSet.getLong("id")).thenReturn(99L);
        when(resultSet.getLong("exam_id")).thenReturn(11L);
        when(resultSet.getLong("student_id")).thenReturn(7L);
        when(resultSet.getString("status")).thenReturn("PROCESSING");
        when(resultSet.getObject("submitted_at", LocalDateTime.class)).thenReturn(submittedAt);
        when(resultSet.getBoolean("timeout_submit")).thenReturn(false);
        when(jdbc.queryForObject(any(String.class), any(RowMapper.class), eq(99L)))
            .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(resultSet, 0));
        RuntimeOutboxService service = new RuntimeOutboxService(jdbc, writer);

        service.submissionAccepted(99L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        String expectedEventId = service.submissionAcceptedEventId(99L);
        verify(writer).append(
            eq(expectedEventId), eq("SUBMISSION"), eq("99"), eq("SubmissionAccepted"), event.capture()
        );
        Map<String, Object> payload = (Map<String, Object>) event.getValue();
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertThat(payload.get("eventType")).isEqualTo("SubmissionAccepted");
        assertThat(payload.get("producer")).isEqualTo("exam-runtime-service");
        assertThat(payload.get("aggregateId")).isEqualTo("99");
        assertThat(payload.get("eventId")).isEqualTo(expectedEventId);
        assertThat(data.get("submissionId")).isEqualTo(99L);
        assertThat(data.get("submittedAt")).isEqualTo(submittedAt);
    }

    @Test
    @SuppressWarnings("unchecked")
    void knownSubmissionContextAvoidsDatabaseReadAndKeepsVersionTwoPayload() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        RuntimeOutboxService service = new RuntimeOutboxService(jdbc, writer);
        LocalDateTime finalizedAt = LocalDateTime.of(2026, 9, 12, 12, 0);
        RuntimeOutboxService.SubmissionAcceptedContext context =
            new RuntimeOutboxService.SubmissionAcceptedContext(
                99L, 11L, 7L, "PROCESSING", finalizedAt, true, "TIMEOUT",
                12L, "payload-hash", finalizedAt, finalizedAt
            );

        SubmissionAcceptedReceipt receipt = service.submissionAcceptedKnown(context);

        verifyNoInteractions(jdbc);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(writer).append(
            eq(service.submissionAcceptedEventId(99L)), eq("SUBMISSION"), eq("99"),
            eq("SubmissionAccepted"), event.capture()
        );
        Map<String, Object> payload = (Map<String, Object>) event.getValue();
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertThat(payload.get("version")).isEqualTo(2);
        assertThat(payload.get("occurredAt")).isEqualTo(finalizedAt);
        assertThat(data)
            .containsEntry("submissionSource", "TIMEOUT")
            .containsEntry("finalSnapshotVersion", 12L)
            .containsEntry("payloadSha256", "payload-hash")
            .containsEntry("runtimeFinalizedAt", finalizedAt);
        assertThat(receipt.submissionId()).isEqualTo(99L);
        assertThat(receipt.timeoutSubmit()).isTrue();
    }
}
