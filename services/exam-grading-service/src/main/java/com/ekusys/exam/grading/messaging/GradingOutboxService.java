package com.ekusys.exam.grading.messaging;

import com.ekusys.exam.common.outbox.OutboxEventWriter;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GradingOutboxService {
    private final JdbcTemplate jdbc;
    private final OutboxEventWriter writer;

    public GradingOutboxService(JdbcTemplate jdbc, OutboxEventWriter writer) {
        this.jdbc = jdbc;
        this.writer = writer;
    }

    public void gradeCompleted(Long submissionId) {
        GradeRow grade = jdbc.queryForObject(
            """
                select gs.runtime_submission_id,gs.exam_id,gs.student_id,gs.exam_name,gs.submitted_at,
                       gr.objective_score,gr.subjective_score,gr.total_score,gr.pass_flag,gr.status,gr.completed_at,gr.grade_revision,gr.answer_version
                  from grading_submission gs
                  join grade_result gr on gr.runtime_submission_id=gs.runtime_submission_id
                 where gs.runtime_submission_id=?
                """,
            (rs, rowNum) -> new GradeRow(
                rs.getLong("runtime_submission_id"), rs.getLong("exam_id"), rs.getLong("student_id"),
                rs.getString("exam_name"), rs.getObject("submitted_at", LocalDateTime.class),
                rs.getInt("objective_score"), rs.getInt("subjective_score"), rs.getInt("total_score"),
                rs.getBoolean("pass_flag"), rs.getString("status"),
                rs.getObject("completed_at", LocalDateTime.class), rs.getLong("grade_revision"), rs.getLong("answer_version")
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
        data.put("gradeRevision", grade.gradeRevision());
        data.put("answerVersion", grade.answerVersion());
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
        writer.append(eventId, "GRADE", aggregateId, "GradeCompleted", event);
    }

    private record GradeRow(Long submissionId, Long examId, Long studentId, String examName,
                            LocalDateTime submittedAt, int objectiveScore, int subjectiveScore,
                            int totalScore, boolean passFlag, String status, LocalDateTime completedAt, long gradeRevision, long answerVersion) {
    }
}
