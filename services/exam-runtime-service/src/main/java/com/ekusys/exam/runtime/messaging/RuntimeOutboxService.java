package com.ekusys.exam.runtime.messaging;

import com.ekusys.exam.common.outbox.OutboxEventWriter;
import java.nio.charset.StandardCharsets;
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

    public SubmissionAcceptedReceipt submissionAccepted(Long submissionId) {
        SubmissionRow submission = jdbc.queryForObject(
            """
                select s.id,s.exam_id,s.student_id,s.status,s.submitted_at,s.timeout_submit,
                       fp.source,fp.snapshot_version,fp.payload_sha256,fp.finalized_at,
                       current_timestamp(3) occurred_at
                  from submission s
                  left join submission_final_payload fp on fp.submission_id=s.id
                 where s.id=?
                """,
            (rs, rowNum) -> new SubmissionRow(
                rs.getLong("id"), rs.getLong("exam_id"), rs.getLong("student_id"), rs.getString("status"),
                rs.getObject("submitted_at", LocalDateTime.class), rs.getBoolean("timeout_submit"),
                rs.getString("source"), rs.getObject("snapshot_version", Long.class),
                rs.getString("payload_sha256"), rs.getObject("finalized_at", LocalDateTime.class),
                rs.getObject("occurred_at", LocalDateTime.class)
            ),
            submissionId
        );
        return submissionAcceptedKnown(new SubmissionAcceptedContext(
            submission.id(), submission.examId(), submission.studentId(), submission.status(),
            submission.submittedAt(), submission.timeoutSubmit(), submission.source(),
            submission.snapshotVersion(), submission.payloadSha256(), submission.finalizedAt(),
            submission.occurredAt()
        ));
    }

    public SubmissionAcceptedReceipt submissionAcceptedKnown(SubmissionAcceptedContext submission) {
        String eventId = submissionAcceptedEventId(submission.submissionId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", submission.submissionId());
        data.put("examId", submission.examId());
        data.put("studentId", submission.studentId());
        data.put("status", submission.status());
        data.put("submittedAt", submission.submittedAt());
        data.put("timeoutSubmit", submission.timeoutSubmit());
        data.put("submissionSource", submission.submissionSource());
        data.put("finalSnapshotVersion", submission.finalSnapshotVersion());
        data.put("payloadSha256", submission.payloadSha256());
        data.put("runtimeFinalizedAt", submission.runtimeFinalizedAt());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "SubmissionAccepted");
        event.put("version", 2);
        event.put("aggregateId", String.valueOf(submission.submissionId()));
        event.put("occurredAt", submission.occurredAt());
        event.put("traceId", null);
        event.put("producer", "exam-runtime-service");
        event.put("data", data);
        writer.append(
            eventId, "SUBMISSION", String.valueOf(submission.submissionId()), "SubmissionAccepted", event
        );
        return new SubmissionAcceptedReceipt(
            submission.submissionId(),
            submission.examId(),
            submission.studentId(),
            submission.submittedAt(),
            submission.runtimeFinalizedAt(),
            submission.finalSnapshotVersion(),
            submission.timeoutSubmit()
        );
    }

    public String submissionAcceptedEventId(Long submissionId) {
        return UUID.nameUUIDFromBytes(
            ("SubmissionAccepted:" + submissionId).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    public void sessionStarted(Long sessionId, Long examId, Long studentId, LocalDateTime eventTime) {
        String eventId = sessionStartedEventId(sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("examId", examId);
        data.put("studentId", studentId);
        data.put("eventType", "SESSION_STARTED");
        data.put("eventTime", eventTime);
        data.put("durationMs", 0);
        data.put("recordEvent", false);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "SessionStarted");
        event.put("version", 1);
        event.put("aggregateId", String.valueOf(sessionId));
        event.put("occurredAt", eventTime);
        event.put("traceId", null);
        event.put("producer", "exam-runtime-service");
        event.put("data", data);
        writer.append(
            eventId, "EXAM_SESSION", String.valueOf(sessionId), "SessionStarted", event
        );
    }

    public String sessionStartedEventId(Long sessionId) {
        return UUID.nameUUIDFromBytes(
            ("SessionStarted:" + sessionId).getBytes(StandardCharsets.UTF_8)
        ).toString();
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
        appendProctoringEvent(UUID.randomUUID().toString(), String.valueOf(eventId), data);
    }

    private void appendProctoringEvent(String eventId, String aggregateId, Map<String, Object> data) {
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
                                 LocalDateTime submittedAt, boolean timeoutSubmit,
                                 String source, Long snapshotVersion, String payloadSha256,
                                 LocalDateTime finalizedAt, LocalDateTime occurredAt) {
    }

    public record SubmissionAcceptedContext(
        Long submissionId,
        Long examId,
        Long studentId,
        String status,
        LocalDateTime submittedAt,
        boolean timeoutSubmit,
        String submissionSource,
        Long finalSnapshotVersion,
        String payloadSha256,
        LocalDateTime runtimeFinalizedAt,
        LocalDateTime occurredAt
    ) {
    }
}
