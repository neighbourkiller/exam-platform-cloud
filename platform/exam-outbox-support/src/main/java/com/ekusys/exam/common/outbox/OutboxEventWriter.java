package com.ekusys.exam.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxEventWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void append(String eventId, String aggregateType, String aggregateId,
                       String eventType, Object payload) {
        try {
            jdbc.update(
                "insert into outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at) "
                    + "values(?,?,?,?,?,'PENDING',current_timestamp(3))",
                eventId, aggregateType, aggregateId, eventType, objectMapper.writeValueAsString(payload)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Outbox 事件写入失败", exception);
        }
    }
}
