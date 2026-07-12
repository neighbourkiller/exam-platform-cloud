package com.ekusys.exam.common.outbox;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxEventWriterTest {

    @Test
    void serializesAndAppendsPendingEvent() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(Map.of("value", 1))).thenReturn("{\"value\":1}");
        OutboxEventWriter writer = new OutboxEventWriter(jdbc, objectMapper);

        writer.append("evt-1", "TEST", "aggregate-1", "TestEvent", Map.of("value", 1));

        verify(jdbc).update(
            contains("values(?,?,?,?,?,'PENDING'"),
            eq("evt-1"), eq("TEST"), eq("aggregate-1"), eq("TestEvent"), eq("{\"value\":1}")
        );
    }
}
