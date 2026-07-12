package com.ekusys.exam.common.audit;

import com.ekusys.exam.common.event.DomainEvent;
import com.ekusys.exam.common.event.EventTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditOutboxService {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String producer;

    public AuditOutboxService(JdbcTemplate jdbc,
                              RabbitTemplate rabbitTemplate,
                              ObjectMapper objectMapper,
                              @Value("${spring.application.name:unknown-service}") String producer) {
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.producer = producer;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEventData data, String traceId) {
        DomainEvent<AuditEventData> event = DomainEvent.create(
            EventTypes.AUDIT_OPERATION_RECORDED,
            data.targetId() == null || data.targetId().isBlank() ? "audit" : data.targetId(),
            traceId,
            producer,
            data
        );
        try {
            jdbc.update(
                "insert into outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at) "
                    + "values(?,'AUDIT',?,?,?,'PENDING',current_timestamp(3))",
                event.eventId(), event.aggregateId(), event.eventType(), objectMapper.writeValueAsString(event)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("审计事件写入 Outbox 失败", exception);
        }
    }

    @Scheduled(fixedDelayString = "${exam.audit.publish-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxRow> rows = jdbc.query(
            "select id,event_type,payload_json from outbox_event "
                + "where status='PENDING' and event_type=? "
                + "and (next_retry_time is null or next_retry_time<=current_timestamp(3)) "
                + "order by created_at limit 100",
            (rs, rowNum) -> new OutboxRow(
                rs.getString("id"), rs.getString("event_type"), rs.getString("payload_json")
            ),
            EventTypes.AUDIT_OPERATION_RECORDED
        );
        for (OutboxRow row : rows) {
            try {
                CorrelationData correlation = new CorrelationData(row.id());
                rabbitTemplate.convertAndSend(AuditRabbitConfiguration.EXCHANGE, row.eventType(), row.payload(), correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
                if (!confirm.ack()) {
                    throw new IllegalStateException("RabbitMQ rejected audit event: " + confirm.reason());
                }
                if (correlation.getReturned() != null) {
                    throw new IllegalStateException("RabbitMQ returned audit event: " + correlation.getReturned());
                }
                jdbc.update(
                    "update outbox_event set status='PUBLISHED',published_at=current_timestamp(3) "
                        + "where id=? and status='PENDING'",
                    row.id()
                );
            } catch (Exception exception) {
                jdbc.update(
                    "update outbox_event set retry_count=retry_count+1," 
                        + "next_retry_time=current_timestamp(3)+interval 5 second where id=? and status='PENDING'",
                    row.id()
                );
            }
        }
    }

    private record OutboxRow(String id, String eventType, String payload) {
    }
}
