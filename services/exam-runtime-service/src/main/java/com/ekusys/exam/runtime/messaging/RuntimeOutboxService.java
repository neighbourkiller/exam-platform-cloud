package com.ekusys.exam.runtime.messaging;

import com.ekusys.exam.common.outbox.OutboxEventWriter;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RuntimeOutboxService {
    private final JdbcTemplate jdbc;
    private final OutboxEventWriter writer;

    public RuntimeOutboxService(JdbcTemplate jdbc, OutboxEventWriter writer) {
        this.jdbc = jdbc;
        this.writer = writer;
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
        writer.append(eventId, "SUBMISSION", String.valueOf(submissionId), "SubmissionAccepted", event);
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
        writer.append(eventId, "PROCTORING", aggregateId, "ProctoringEventRecorded", event);
    }

    private record SubmissionRow(Long id, Long examId, Long studentId, String status,
                                 LocalDateTime submittedAt, boolean timeoutSubmit) {
    }
}
