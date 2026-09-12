package com.ekusys.exam.reporting.messaging;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReportingEventConsumer {
    private static final String CONSUMER = "reporting-projection";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReportingEventConsumer(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(
        queues = ReportingRabbitConfig.QUEUE,
        containerFactory = "reportingEventsListenerContainerFactory"
    )
    @Transactional
    public void consume(String payload) throws Exception {
        JsonNode event = objectMapper.readTree(payload);
        String eventId = requiredText(event, "eventId");
        int inserted = jdbc.update(
            "insert ignore into inbox_event(event_id,consumer_name,processed_at) values(?,?,current_timestamp(3))",
            eventId, CONSUMER
        );
        if (inserted == 0) {
            return;
        }
        JsonNode data = event.path("data");
        switch (requiredText(event, "eventType")) {
            case "ExamPublished" -> projectExam(data);
            case "ExamTerminated" -> jdbc.update(
                "update rpt_exam set status='TERMINATED',updated_at=current_timestamp(3) where exam_id=?",
                requiredLong(data, "examId")
            );
            case "SubmissionAccepted" -> projectSubmission(data);
            case "SessionStarted" -> projectProctoring(data);
            case "ProctoringEventRecorded" -> projectProctoring(data);
            case "GradeCompleted" -> projectGrade(data);
            case "AuditOperationRecorded" -> projectAudit(data);
            default -> throw new IllegalArgumentException("不支持的报表事件类型");
        }
    }

    private void projectAudit(JsonNode data) {
        jdbc.update(
            """
                insert into operation_audit_log(id,operator_id,operator_username,operator_roles,action,target_type,
                    target_id,request_method,request_path,request_ip,detail,status,error_message,operate_time,create_time)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,current_timestamp(3))
                """,
            IdWorker.getId(), nullableLong(data, "operatorId"), nullableText(data, "operatorUsername"),
            nullableText(data, "operatorRoles"), requiredText(data, "action"), requiredText(data, "targetType"),
            nullableText(data, "targetId"), nullableText(data, "requestMethod"), nullableText(data, "requestPath"),
            nullableText(data, "requestIp"), nullableText(data, "detail"), requiredText(data, "status"),
            nullableText(data, "errorMessage"), LocalDateTime.parse(requiredText(data, "operateTime"))
        );
    }

    private void projectExam(JsonNode data) {
        Long examId = requiredLong(data, "examId");
        jdbc.update(
            """
                insert into rpt_exam(exam_id,name,subject_id,subject_name,start_time,end_time,duration_minutes,
                                     pass_score,status,publisher_id,updated_at)
                values(?,?,?,?,?,?,?,?,?,?,current_timestamp(3))
                on duplicate key update name=values(name),subject_id=values(subject_id),subject_name=values(subject_name),
                    start_time=values(start_time),end_time=values(end_time),duration_minutes=values(duration_minutes),
                    pass_score=values(pass_score),status=values(status),publisher_id=values(publisher_id),
                    updated_at=current_timestamp(3)
                """,
            examId, requiredText(data, "name"), nullableLong(data, "subjectId"), nullableText(data, "subjectName"),
            LocalDateTime.parse(requiredText(data, "startTime")), LocalDateTime.parse(requiredText(data, "endTime")), requiredInt(data, "durationMinutes"),
            requiredInt(data, "passScore"), requiredText(data, "status"), nullableLong(data, "publisherId")
        );
        jdbc.update("delete from rpt_exam_candidate where exam_id=?", examId);
        for (JsonNode candidate : data.path("candidates")) {
            jdbc.update(
                "insert into rpt_exam_candidate(exam_id,student_id,class_id,class_name,updated_at) values(?,?,?,?,current_timestamp(3))",
                examId, requiredLong(candidate, "studentId"), nullableLong(candidate, "classId"),
                nullableText(candidate, "className")
            );
            jdbc.update(
                """
                    insert into rpt_proctoring_student(id,exam_id,student_id,class_names_json,event_count,updated_at)
                    values(?,?,?,json_array(?),0,current_timestamp(3))
                    on duplicate key update class_names_json=values(class_names_json),updated_at=current_timestamp(3)
                    """,
                IdWorker.getId(), examId, requiredLong(candidate, "studentId"), nullableText(candidate, "className")
            );
        }
    }

    private void projectGrade(JsonNode data) {
        jdbc.update(
            """
                insert into rpt_student_score(submission_id,exam_id,student_id,status,objective_score,subjective_score,
                                              total_score,pass_flag,submitted_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,current_timestamp(3))
                on duplicate key update status=values(status),objective_score=values(objective_score),
                    subjective_score=values(subjective_score),total_score=values(total_score),pass_flag=values(pass_flag),
                    submitted_at=values(submitted_at),updated_at=current_timestamp(3)
                """,
            requiredLong(data, "submissionId"), requiredLong(data, "examId"), requiredLong(data, "studentId"),
            requiredText(data, "status"), requiredInt(data, "objectiveScore"), requiredInt(data, "subjectiveScore"),
            requiredInt(data, "totalScore"), data.path("passFlag").asBoolean(), LocalDateTime.parse(requiredText(data, "submittedAt"))
        );
        Long submissionId = requiredLong(data, "submissionId");
        jdbc.update(
            """
                update rpt_proctoring_student
                   set session_status='SUBMITTED',submission_status='GRADED',
                       updated_at=current_timestamp(3)
                 where exam_id=? and student_id=?
                """,
            requiredLong(data, "examId"), requiredLong(data, "studentId")
        );
        jdbc.update("delete from rpt_objective_answer where submission_id=?", submissionId);
        for (JsonNode result : data.path("questionResults")) {
            jdbc.update(
                "insert into rpt_objective_answer(submission_id,exam_id,student_id,question_id,question_content,correct_flag,updated_at) values(?,?,?,?,?,?,current_timestamp(3))",
                submissionId, requiredLong(data, "examId"), requiredLong(data, "studentId"),
                requiredLong(result, "questionId"), nullableText(result, "questionContent"), result.path("correct").asBoolean()
            );
        }
    }

    private void projectSubmission(JsonNode data) {
        jdbc.update(
            """
                insert into rpt_student_score(
                    submission_id,exam_id,student_id,status,submitted_at,timeout_submit,
                    submission_source,runtime_finalized_at,final_snapshot_version,payload_sha256,updated_at
                ) values(?,?,?,?,?,?,?,?,?,?,current_timestamp(3))
                on duplicate key update status=case when status='GRADED' then status else values(status) end,
                    submitted_at=coalesce(submitted_at,values(submitted_at)),
                    timeout_submit=coalesce(values(timeout_submit),timeout_submit),
                    submission_source=coalesce(values(submission_source),submission_source),
                    runtime_finalized_at=coalesce(values(runtime_finalized_at),runtime_finalized_at),
                    final_snapshot_version=coalesce(values(final_snapshot_version),final_snapshot_version),
                    payload_sha256=coalesce(values(payload_sha256),payload_sha256),
                    updated_at=current_timestamp(3)
                """,
            requiredLong(data, "submissionId"), requiredLong(data, "examId"), requiredLong(data, "studentId"),
            requiredText(data, "status"), LocalDateTime.parse(requiredText(data, "submittedAt")),
            nullableBoolean(data, "timeoutSubmit"), nullableText(data, "submissionSource"),
            nullableDateTime(data, "runtimeFinalizedAt"), nullableLong(data, "finalSnapshotVersion"),
            nullableText(data, "payloadSha256")
        );
        jdbc.update(
            """
                update rpt_proctoring_student
                   set session_status='SUBMITTED',
                       submission_status=case when submission_status='GRADED' then submission_status else ? end,
                       timeout_submit=coalesce(?,timeout_submit),
                       submission_source=coalesce(?,submission_source),
                       runtime_finalized_at=coalesce(?,runtime_finalized_at),
                       updated_at=current_timestamp(3)
                 where exam_id=? and student_id=?
                """,
            requiredText(data, "status"), nullableBoolean(data, "timeoutSubmit"),
            nullableText(data, "submissionSource"), nullableDateTime(data, "runtimeFinalizedAt"),
            requiredLong(data, "examId"), requiredLong(data, "studentId")
        );
    }

    private void projectProctoring(JsonNode data) {
        boolean recordEvent = data.path("recordEvent").asBoolean(true);
        jdbc.update(
            """
                insert into rpt_proctoring_student(id,exam_id,student_id,session_status,event_count,last_event_time,latest_event_type,updated_at)
                values(?,?,?,'ANSWERING',?,?,?,current_timestamp(3))
                on duplicate key update session_status='ANSWERING',
                    event_count=event_count+?,last_event_time=case when ?=1 then values(last_event_time) else last_event_time end,
                    latest_event_type=case when ?=1 then values(latest_event_type) else latest_event_type end,
                    updated_at=current_timestamp(3)
                """,
            IdWorker.getId(), requiredLong(data, "examId"), requiredLong(data, "studentId"),
            recordEvent ? 1 : 0, LocalDateTime.parse(requiredText(data, "eventTime")),
            requiredText(data, "eventType"), recordEvent ? 1 : 0, recordEvent ? 1 : 0, recordEvent ? 1 : 0
        );
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("事件缺少字段: " + field);
        }
        return value.asText();
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("事件缺少字段: " + field);
        }
        return value.longValue();
    }

    private Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.longValue();
    }

    private Boolean nullableBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private LocalDateTime nullableDateTime(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("事件缺少字段: " + field);
        }
        return value.intValue();
    }
}
