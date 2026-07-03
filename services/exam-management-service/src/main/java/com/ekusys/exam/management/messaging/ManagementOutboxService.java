package com.ekusys.exam.management.messaging;

import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.repository.entity.Exam;
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
public class ManagementOutboxService {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;

    public ManagementOutboxService(JdbcTemplate jdbc, RabbitTemplate rabbit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
    }

    public void examPublished(Exam exam, PaperSnapshotView paper, SubjectSummary subject,
                              Map<Long, String> classNames) {
        List<Map<String, Object>> candidates = jdbc.query(
            "select student_id,class_id from exam_candidate where exam_id=? order by student_id",
            (rs, rowNum) -> Map.of(
                "studentId", rs.getLong("student_id"),
                "classId", rs.getLong("class_id"),
                "className", classNames.getOrDefault(rs.getLong("class_id"), "")
            ),
            exam.getId()
        );
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("examId", exam.getId());
        data.put("name", exam.getName());
        data.put("subjectId", paper.subjectId());
        data.put("subjectName", subject == null ? null : subject.name());
        data.put("startTime", exam.getStartTime());
        data.put("endTime", exam.getEndTime());
        data.put("durationMinutes", exam.getDurationMinutes());
        data.put("passScore", exam.getPassScore());
        data.put("status", exam.getStatus());
        data.put("publisherId", exam.getPublisherId());
        data.put("candidates", candidates);
        append("ExamPublished", String.valueOf(exam.getId()), data);
    }

    public void examTerminated(Exam exam) {
        append("ExamTerminated", String.valueOf(exam.getId()), Map.of("examId", exam.getId()));
    }

    private void append(String eventType, String aggregateId, Map<String, Object> data) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", eventType);
        event.put("version", 1);
        event.put("aggregateId", aggregateId);
        event.put("occurredAt", LocalDateTime.now());
        event.put("traceId", null);
        event.put("producer", "exam-management-service");
        event.put("data", data);
        try {
            jdbc.update(
                "insert into outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at) values(?,'EXAM',?,?,?,'PENDING',current_timestamp(3))",
                eventId, aggregateId, eventType, objectMapper.writeValueAsString(event)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("考试事件序列化失败", exception);
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxRow> rows = jdbc.query(
            "select id,event_type,payload_json from outbox_event where status='PENDING' and (next_retry_time is null or next_retry_time<=current_timestamp(3)) order by created_at limit 100",
            (rs, rowNum) -> new OutboxRow(rs.getString("id"), rs.getString("event_type"), rs.getString("payload_json"))
        );
        for (OutboxRow row : rows) {
            try {
                CorrelationData correlation = new CorrelationData(row.id());
                rabbit.convertAndSend(ManagementRabbitConfig.EXCHANGE, row.eventType(), row.payload(), correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
                if (!confirm.ack()) throw new IllegalStateException("RabbitMQ rejected event: " + confirm.reason());
                jdbc.update("update outbox_event set status='PUBLISHED',published_at=current_timestamp(3) where id=?", row.id());
            } catch (Exception exception) {
                jdbc.update("update outbox_event set retry_count=retry_count+1,next_retry_time=current_timestamp(3)+interval 5 second where id=?", row.id());
            }
        }
    }

    private record OutboxRow(String id, String eventType, String payload) {
    }
}
