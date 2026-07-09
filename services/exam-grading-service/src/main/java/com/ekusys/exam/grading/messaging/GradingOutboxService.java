package com.ekusys.exam.grading.messaging;

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
public class GradingOutboxService {
    private static final String EXCHANGE = "exam.events";

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;

    public GradingOutboxService(JdbcTemplate jdbc, RabbitTemplate rabbit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
    }

    public void gradeCompleted(Long submissionId) {
        GradeRow grade = jdbc.queryForObject(
            """
                select gs.runtime_submission_id,gs.exam_id,gs.student_id,gs.exam_name,gs.submitted_at,
                       gr.objective_score,gr.subjective_score,gr.total_score,gr.pass_flag,gr.status,gr.completed_at
                  from grading_submission gs
                  join grade_result gr on gr.runtime_submission_id=gs.runtime_submission_id
                 where gs.runtime_submission_id=?
                """,
            (rs, rowNum) -> new GradeRow(
                rs.getLong("runtime_submission_id"), rs.getLong("exam_id"), rs.getLong("student_id"),
                rs.getString("exam_name"), rs.getObject("submitted_at", LocalDateTime.class),
                rs.getInt("objective_score"), rs.getInt("subjective_score"), rs.getInt("total_score"),
                rs.getBoolean("pass_flag"), rs.getString("status"),
                rs.getObject("completed_at", LocalDateTime.class)
            ),
            submissionId
        );
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", grade.submissionId());
        data.put("examId", grade.examId());
        data.put("studentId", grade.studentId());
        data.put("examName", grade.examName());
        data.put("status", grade.status());
        data.put("objectiveScore", grade.objectiveScore());
        data.put("subjectiveScore", grade.subjectiveScore());
        data.put("totalScore", grade.totalScore());
        data.put("passFlag", grade.passFlag());
        data.put("submittedAt", grade.submittedAt());
        data.put("completedAt", grade.completedAt());
        data.put("questionResults", jdbc.query(
            "select question_id,question_content,correct_flag from grading_answer_result where runtime_submission_id=? and objective_flag=1 order by question_id",
            (rs, rowNum) -> Map.of(
                "questionId", rs.getLong("question_id"),
                "questionContent", rs.getString("question_content"),
                "correct", rs.getBoolean("correct_flag")
            ),
            submissionId
        ));
        append(String.valueOf(submissionId), data);
    }

    private void append(String aggregateId, Map<String, Object> data) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "GradeCompleted");
        event.put("version", 1);
        event.put("aggregateId", aggregateId);
        event.put("occurredAt", LocalDateTime.now());
        event.put("traceId", null);
        event.put("producer", "exam-grading-service");
        event.put("data", data);
        try {
            jdbc.update(
                "insert into outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at) values(?,'GRADE',?,'GradeCompleted',?,'PENDING',current_timestamp(3))",
                eventId, aggregateId, objectMapper.writeValueAsString(event)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("成绩事件序列化失败", exception);
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxRow> rows = jdbc.query(
            "select id,event_type,payload_json from outbox_event where status='PENDING' and event_type<>'AuditOperationRecorded' and (next_retry_time is null or next_retry_time<=current_timestamp(3)) order by created_at limit 100",
            (rs, rowNum) -> new OutboxRow(rs.getString("id"), rs.getString("event_type"), rs.getString("payload_json"))
        );
        for (OutboxRow row : rows) {
            try {
                CorrelationData correlation = new CorrelationData(row.id());
                rabbit.convertAndSend(EXCHANGE, row.eventType(), row.payload(), correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
                if (!confirm.ack()) throw new IllegalStateException("RabbitMQ rejected event: " + confirm.reason());
                jdbc.update("update outbox_event set status='PUBLISHED',published_at=current_timestamp(3) where id=?", row.id());
            } catch (Exception exception) {
                jdbc.update("update outbox_event set retry_count=retry_count+1,next_retry_time=current_timestamp(3)+interval 5 second where id=?", row.id());
            }
        }
    }

    private record GradeRow(Long submissionId, Long examId, Long studentId, String examName,
                            LocalDateTime submittedAt, int objectiveScore, int subjectiveScore,
                            int totalScore, boolean passFlag, String status, LocalDateTime completedAt) {
    }

    private record OutboxRow(String id, String eventType, String payload) {
    }
}
