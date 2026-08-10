package com.ekusys.exam.runtime.entry;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.runtime.config.ExamEntryProperties;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExamProvisioningService {
    public static final String CONSUMER_NAME = "runtime-exam-lifecycle-v2";

    private final JdbcTemplate jdbc;
    private final RuntimeExamDefinitionRepository definitions;
    private final PaperDeliveryCache paperCache;
    private final ExamEntryProperties properties;
    private final ExamEntryMetrics metrics;
    private final TransactionTemplate transactions;

    public ExamProvisioningService(
        JdbcTemplate jdbc,
        RuntimeExamDefinitionRepository definitions,
        PaperDeliveryCache paperCache,
        ExamEntryProperties properties,
        ExamEntryMetrics metrics,
        @Qualifier("examProvisioningTransactionTemplate") TransactionTemplate transactions
    ) {
        this.jdbc = jdbc;
        this.definitions = definitions;
        this.paperCache = paperCache;
        this.properties = properties;
        this.metrics = metrics;
        this.transactions = transactions;
    }

    public ExamProvisioningView provision(ExamProvisioningCommand command) {
        RuntimeExamDefinition existing = definitions.find(command.examId());
        if (alreadyProcessed(command.eventId())) {
            return existing == null
                ? new ExamProvisioningView(command.examId(), "READY", 0, 0)
                : ExamProvisioningView.from(existing);
        }

        List<Long> candidates = normalizedCandidates(command.candidateIds());
        metrics.provisioningStarted(candidates.size());
        try {
            transactions.executeWithoutResult(status -> begin(command, candidates.size()));
            RuntimeExamDefinition definition = requireDefinition(command.examId());
            if (definition.terminated()) {
                transactions.executeWithoutResult(status -> recordInbox(
                    command.eventId(), "ExamPublished"
                ));
                return ExamProvisioningView.from(definition);
            }

            int batchSize = properties.safeProvisioningBatchSize();
            for (int offset = 0; offset < candidates.size(); offset += batchSize) {
                List<Long> batch = candidates.subList(offset, Math.min(candidates.size(), offset + batchSize));
                transactions.executeWithoutResult(status -> createBatch(command, batch));
                metrics.provisioningProgress(candidates.size() - Math.min(candidates.size(), offset + batchSize));
            }

            Counts counts = verify(command.examId());
            if (counts.sessions() != candidates.size()
                || counts.submissions() != candidates.size()
                || counts.timeoutTasks() != candidates.size()) {
                throw new IllegalStateException(
                    "Runtime 预创建数量不一致: candidates=" + candidates.size()
                        + ", sessions=" + counts.sessions()
                        + ", submissions=" + counts.submissions()
                        + ", timeoutTasks=" + counts.timeoutTasks()
                );
            }

            paperCache.prewarm(command.paperSnapshotId());
            transactions.executeWithoutResult(status -> finish(command, candidates.size()));
            return ExamProvisioningView.from(requireDefinition(command.examId()));
        } catch (RuntimeException exception) {
            markFailed(command.examId(), exception.getMessage());
            throw exception;
        } finally {
            metrics.provisioningFinished();
        }
    }

    public ExamProvisioningView terminate(String eventId, int eventVersion, Long examId) {
        if (!alreadyProcessed(eventId)) {
            transactions.executeWithoutResult(status -> {
                jdbc.update(
                    """
                        insert into runtime_exam_definition(
                            exam_id,exam_status,provisioning_status,source_event_id,
                            source_event_version,candidate_count,prepared_count,created_at,updated_at
                        ) values(?,'TERMINATED','TERMINATED',?,?,0,0,current_timestamp(3),current_timestamp(3))
                        on duplicate key update exam_status='TERMINATED',provisioning_status='TERMINATED',
                            source_event_id=values(source_event_id),
                            source_event_version=greatest(source_event_version,values(source_event_version)),
                            last_error=null,updated_at=current_timestamp(3)
                        """,
                    examId, eventId, eventVersion
                );
                jdbc.update(
                    """
                        update submission_timeout_task t
                        join exam_session s on s.id=t.session_id
                           set t.status='CANCELLED',t.due_at=null,t.claim_token=null,
                               t.lease_until=null,t.next_retry_at=null,t.updated_at=current_timestamp(3)
                         where s.exam_id=? and s.status='PREPARED' and t.status='WAITING'
                        """,
                    examId
                );
                jdbc.update(
                    """
                        update submission sub
                        join exam_session s
                          on s.exam_id=sub.exam_id and s.student_id=sub.student_id
                           set sub.status='CANCELLED',sub.update_time=current_timestamp(3)
                         where s.exam_id=? and s.status='PREPARED' and sub.status='IN_PROGRESS'
                        """,
                    examId
                );
                jdbc.update(
                    """
                        update exam_session
                           set status='CANCELLED',end_time=current_timestamp(3),update_time=current_timestamp(3)
                         where exam_id=? and status='PREPARED'
                        """,
                    examId
                );
                recordInbox(eventId, "ExamTerminated");
            });
        }
        return ExamProvisioningView.from(requireDefinition(examId));
    }

    private void begin(ExamProvisioningCommand command, int candidateCount) {
        jdbc.update(
            """
                insert into runtime_exam_definition(
                    exam_id,name,start_time,end_time,duration_minutes,pass_score,
                    paper_snapshot_id,paper_snapshot_version,publisher_id,proctoring_level,
                    proctoring_config_json,exam_status,provisioning_status,source_event_id,
                    source_event_version,candidate_count,prepared_count,last_error,created_at,updated_at
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,'PROVISIONING',?,?,?,0,null,current_timestamp(3),current_timestamp(3))
                on duplicate key update
                    name=if(provisioning_status='TERMINATED',name,values(name)),
                    start_time=if(provisioning_status='TERMINATED',start_time,values(start_time)),
                    end_time=if(provisioning_status='TERMINATED',end_time,values(end_time)),
                    duration_minutes=if(provisioning_status='TERMINATED',duration_minutes,values(duration_minutes)),
                    pass_score=if(provisioning_status='TERMINATED',pass_score,values(pass_score)),
                    paper_snapshot_id=if(provisioning_status='TERMINATED',paper_snapshot_id,values(paper_snapshot_id)),
                    paper_snapshot_version=if(provisioning_status='TERMINATED',paper_snapshot_version,values(paper_snapshot_version)),
                    publisher_id=if(provisioning_status='TERMINATED',publisher_id,values(publisher_id)),
                    proctoring_level=if(provisioning_status='TERMINATED',proctoring_level,values(proctoring_level)),
                    proctoring_config_json=if(provisioning_status='TERMINATED',proctoring_config_json,values(proctoring_config_json)),
                    exam_status=if(provisioning_status='TERMINATED','TERMINATED',values(exam_status)),
                    provisioning_status=if(provisioning_status='TERMINATED','TERMINATED','PROVISIONING'),
                    source_event_id=if(provisioning_status='TERMINATED',source_event_id,values(source_event_id)),
                    source_event_version=greatest(source_event_version,values(source_event_version)),
                    candidate_count=if(provisioning_status='TERMINATED',candidate_count,values(candidate_count)),
                    last_error=if(provisioning_status='TERMINATED',last_error,null),
                    updated_at=current_timestamp(3)
                """,
            command.examId(), command.name(), command.startTime(), command.endTime(),
            command.durationMinutes(), command.passScore(), command.paperSnapshotId(),
            command.paperSnapshotVersion(), command.publisherId(), command.proctoringLevel(),
            command.proctoringConfigJson(), command.examStatus(), command.eventId(),
            command.eventVersion(), candidateCount
        );
    }

    private void createBatch(ExamProvisioningCommand command, List<Long> studentIds) {
        String lifecycle = jdbc.queryForObject(
            "select provisioning_status from runtime_exam_definition where exam_id=? for update",
            String.class,
            command.examId()
        );
        if ("TERMINATED".equals(lifecycle)) {
            return;
        }

        List<NewSession> newSessions = studentIds.stream()
            .map(studentId -> new NewSession(IdWorker.getId(), studentId))
            .toList();
        jdbc.batchUpdate(
            """
                insert ignore into exam_session(
                    id,exam_id,student_id,status,start_time,deadline_time,claim_time,
                    create_time,update_time
                ) values(?,?,?,'PREPARED',null,null,null,current_timestamp(3),current_timestamp(3))
                """,
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                    NewSession row = newSessions.get(index);
                    statement.setLong(1, row.sessionId());
                    statement.setLong(2, command.examId());
                    statement.setLong(3, row.studentId());
                }

                @Override
                public int getBatchSize() {
                    return newSessions.size();
                }
            }
        );

        List<PreparedSession> sessions = loadSessions(command.examId(), studentIds);
        jdbc.batchUpdate(
            """
                insert ignore into submission(
                    id,exam_id,student_id,status,paper_snapshot_id,timeout_submit,
                    draft_version,create_time,update_time
                ) values(?,?,?,'IN_PROGRESS',?,0,0,current_timestamp(3),current_timestamp(3))
                """,
            sessions,
            sessions.size(),
            (statement, row) -> {
                statement.setLong(1, IdWorker.getId());
                statement.setLong(2, command.examId());
                statement.setLong(3, row.studentId());
                statement.setLong(4, command.paperSnapshotId());
            }
        );
        jdbc.batchUpdate(
            """
                insert ignore into submission_timeout_task(
                    id,session_id,exam_id,student_id,due_at,status,created_at,updated_at
                ) values(?,?,?,?,?,?,current_timestamp(3),current_timestamp(3))
                """,
            sessions,
            sessions.size(),
            (statement, row) -> {
                statement.setLong(1, row.sessionId());
                statement.setLong(2, row.sessionId());
                statement.setLong(3, command.examId());
                statement.setLong(4, row.studentId());
                statement.setObject(5, row.deadline());
                statement.setString(6, row.active() ? "PENDING" : "WAITING");
            }
        );
        jdbc.update(
            """
                update runtime_exam_definition
                   set prepared_count=(select count(*) from exam_session where exam_id=?),
                       updated_at=current_timestamp(3)
                 where exam_id=? and provisioning_status='PROVISIONING'
                """,
            command.examId(), command.examId()
        );
    }

    private List<PreparedSession> loadSessions(Long examId, List<Long> studentIds) {
        String placeholders = String.join(",", Collections.nCopies(studentIds.size(), "?"));
        List<Object> arguments = new ArrayList<>(studentIds.size() + 1);
        arguments.add(examId);
        arguments.addAll(studentIds);
        return jdbc.query(
            "select id,student_id,status,deadline_time from exam_session where exam_id=? and student_id in ("
                + placeholders + ")",
            (rs, rowNum) -> new PreparedSession(
                rs.getLong("id"), rs.getLong("student_id"), rs.getString("status"),
                rs.getObject("deadline_time", LocalDateTime.class)
            ),
            arguments.toArray()
        );
    }

    private Counts verify(Long examId) {
        return jdbc.queryForObject(
            """
                select count(distinct s.student_id) session_count,
                       count(distinct sub.student_id) submission_count,
                       count(distinct t.student_id) timeout_count
                  from exam_session s
                  left join submission sub
                    on sub.exam_id=s.exam_id and sub.student_id=s.student_id
                  left join submission_timeout_task t on t.session_id=s.id
                 where s.exam_id=?
                """,
            (rs, rowNum) -> new Counts(
                rs.getInt("session_count"), rs.getInt("submission_count"),
                rs.getInt("timeout_count")
            ),
            examId
        );
    }

    private void finish(ExamProvisioningCommand command, int candidateCount) {
        jdbc.update(
            """
                update runtime_exam_definition
                   set provisioning_status='READY',exam_status='PUBLISHED',
                       prepared_count=?,last_error=null,updated_at=current_timestamp(3)
                 where exam_id=? and provisioning_status<>'TERMINATED'
                """,
            candidateCount, command.examId()
        );
        recordInbox(command.eventId(), "ExamPublished");
    }

    private void markFailed(Long examId, String error) {
        try {
            transactions.executeWithoutResult(status -> jdbc.update(
                """
                    update runtime_exam_definition
                       set provisioning_status='FAILED',last_error=?,updated_at=current_timestamp(3)
                     where exam_id=? and provisioning_status<>'TERMINATED'
                    """,
                abbreviate(error), examId
            ));
        } catch (RuntimeException ignored) {
            // Preserve the original provisioning exception for Rabbit retry/DLQ handling.
        }
    }

    private boolean alreadyProcessed(String eventId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from inbox_event where event_id=? and consumer_name=?",
            Integer.class,
            eventId, CONSUMER_NAME
        );
        return count != null && count > 0;
    }

    private void recordInbox(String eventId, String eventType) {
        jdbc.update(
            """
                insert ignore into inbox_event(event_id,consumer_name,event_type,processed_at)
                values(?,?,?,current_timestamp(3))
                """,
            eventId, CONSUMER_NAME, eventType
        );
    }

    private RuntimeExamDefinition requireDefinition(Long examId) {
        RuntimeExamDefinition definition = definitions.find(examId);
        if (definition == null) {
            throw new IllegalStateException("Runtime 考试投影不存在: " + examId);
        }
        return definition;
    }

    private List<Long> normalizedCandidates(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        candidateIds.stream().filter(java.util.Objects::nonNull).sorted().forEach(unique::add);
        return List.copyOf(unique);
    }

    private String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private record NewSession(Long sessionId, Long studentId) {
    }

    private record PreparedSession(Long sessionId, Long studentId, String status,
                                   LocalDateTime deadline) {
        boolean active() {
            return deadline != null && ("ANSWERING".equals(status) || "AUTO_SUBMITTING".equals(status));
        }
    }

    private record Counts(int sessions, int submissions, int timeoutTasks) {
    }
}
