package com.ekusys.exam.runtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
}
