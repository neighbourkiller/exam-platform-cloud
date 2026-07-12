package com.ekusys.exam.management.messaging;

import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.common.outbox.OutboxEventWriter;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.repository.entity.Exam;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ManagementOutboxService {
    private final JdbcTemplate jdbc;
    private final OutboxEventWriter writer;

    public ManagementOutboxService(JdbcTemplate jdbc, OutboxEventWriter writer) {
        this.jdbc = jdbc;
        this.writer = writer;
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
        writer.append(eventId, "EXAM", aggregateId, eventType, event);
    }
}
