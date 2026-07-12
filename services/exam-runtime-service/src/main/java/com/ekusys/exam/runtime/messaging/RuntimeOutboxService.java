package com.ekusys.exam.runtime.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

@Service
public class RuntimeOutboxService {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;

    public RuntimeOutboxService(JdbcTemplate jdbc, RabbitTemplate rabbit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
    }

    public void submissionAccepted(Long submissionId) {
        SubmissionRow submission = jdbc.queryForObject(
            "select id,exam_id,student_id,status,submitted_at,timeout_submit from submission where id=?",
            (rs, rowNum) -> new SubmissionRow(
                rs.getLong("id"), rs.getLong("exam_id"), rs.getLong("student_id"), rs.getString("status"),
                rs.getObject("submitted_at", LocalDateTime.class), rs.getBoolean("timeout_submit")
            ),
            submissionId
        );
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", submission.id());
        data.put("examId", submission.examId());
        data.put("studentId", submission.studentId());
        data.put("status", submission.status());
        data.put("submittedAt", submission.submittedAt());
        data.put("timeoutSubmit", submission.timeoutSubmit());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "SubmissionAccepted");
        event.put("version", 1);
        event.put("aggregateId", String.valueOf(submissionId));
        event.put("occurredAt", LocalDateTime.now());
        event.put("traceId", null);
        event.put("producer", "exam-runtime-service");
        event.put("data", data);
        try {
            jdbc.update(
                "insert into outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at) values(?,'SUBMISSION',?,'SubmissionAccepted',?,'PENDING',current_timestamp(3))",
                eventId, String.valueOf(submissionId), objectMapper.writeValueAsString(event)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("交卷事件序列化失败", exception);
        }
    }

    public void sessionStarted(Long examId, Long studentId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("examId", examId);
        data.put("studentId", studentId);
        data.put("eventType", "SESSION_STARTED");
        data.put("eventTime", LocalDateTime.now());
        data.put("durationMs", 0);
        data.put("recordEvent", false);
        appendProctoringEvent(examId + ":" + studentId, data);
    }

    public void proctoringEvent(Long eventId) {
        Map<String, Object> data = jdbc.queryForObject(
            "select exam_id,student_id,event_type,event_time,duration_ms from anti_cheat_event where id=?",
            (rs, rowNum) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("examId", rs.getLong("exam_id"));
                value.put("studentId", rs.getLong("student_id"));
                value.put("eventType", rs.getString("event_type"));
                value.put("eventTime", rs.getObject("event_time", LocalDateTime.class));
                value.put("durationMs", rs.getObject("duration_ms") == null ? 0 : rs.getLong("duration_ms"));
                value.put("recordEvent", true);
                return value;
            }, eventId);
        appendProctoringEvent(String.valueOf(eventId), data);
    }

    private void appendProctoringEvent(String aggregateId, Map<String, Object> data) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "ProctoringEventRecorded");
        event.put("version", 1);
        event.put("aggregateId", aggregateId);
        event.put("occurredAt", LocalDateTime.now());
        event.put("traceId", null);
        event.put("producer", "exam-runtime-service");
        event.put("data", data);
        try {
            jdbc.update(
                "insert into outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at) values(?,'PROCTORING',?,'ProctoringEventRecorded',?,'PENDING',current_timestamp(3))",
                eventId, aggregateId, objectMapper.writeValueAsString(event)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("监考事件序列化失败", exception);
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay-ms:1000}")
    @Transactional
    public void publish() {
        List<OutboxRow> rows = jdbc.query(
            "select id,event_type,payload_json from outbox_event where status='PENDING' and event_type<>'AuditOperationRecorded' and (next_retry_time is null or next_retry_time<=current_timestamp(3)) order by created_at limit 100",
            (rs, rowNum) -> new OutboxRow(rs.getString("id"), rs.getString("event_type"), rs.getString("payload_json"))
        );
        for (OutboxRow row : rows) {
            try {
                CorrelationData correlation = new CorrelationData(row.id());
                rabbit.convertAndSend(RuntimeRabbitConfig.EXCHANGE, row.eventType(), row.payload(), correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
                if (!confirm.ack()) throw new IllegalStateException("RabbitMQ rejected event: " + confirm.reason());
                if (correlation.getReturned() != null) {
                    throw new IllegalStateException("RabbitMQ returned event: " + correlation.getReturned());
                }
                jdbc.update("update outbox_event set status='PUBLISHED',published_at=current_timestamp(3) where id=?", row.id());
            } catch (Exception exception) {
                jdbc.update("update outbox_event set retry_count=retry_count+1,next_retry_time=current_timestamp(3)+interval 5 second where id=?", row.id());
            }
        }
    }

    private record SubmissionRow(Long id, Long examId, Long studentId, String status,
                                 LocalDateTime submittedAt, boolean timeoutSubmit) {
    }

    private record OutboxRow(String id, String eventType, String payload) {
    }
}
