package com.ekusys.exam.grading.service;

import com.ekusys.exam.content.api.QuestionCorrectionCommand;
import com.ekusys.exam.grading.api.GradeProjectionQuery;
import com.ekusys.exam.grading.client.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RegradeWorker {
    private static final Logger LOG = LoggerFactory.getLogger(RegradeWorker.class);
    private final JdbcTemplate jdbc;
    private final AnswerKeyService keys;
    private final GradingService grading;
    private final RuntimeGradingClient inputs;
    private final ContentGradingClient snapshots;
    private final RegradeClients.Runtime runtime;
    private final RegradeClients.Content bank;
    private final RegradeClients.Reporting reporting;
    private final TransactionTemplate tx;
    private final org.springframework.core.task.TaskExecutor executor;
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    @Value("${app.regrading.worker-enabled:true}")
    private boolean enabled = true;
    public RegradeWorker(JdbcTemplate jdbc, AnswerKeyService keys, GradingService grading, RuntimeGradingClient inputs,
            ContentGradingClient snapshots, RegradeClients.Runtime runtime, RegradeClients.Content bank,
            RegradeClients.Reporting reporting, PlatformTransactionManager manager,
            @org.springframework.beans.factory.annotation.Qualifier("regradeExecutor") org.springframework.core.task.TaskExecutor executor) {
        this.jdbc = jdbc; this.keys = keys; this.grading = grading; this.inputs = inputs;
        this.snapshots = snapshots; this.runtime = runtime; this.bank = bank; this.reporting = reporting; this.executor = executor;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString="${app.regrading.poll-ms:2000}")
    public void dispatch() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { tick(); }
                catch (RuntimeException failure) {
                    LOG.warn("重判后台任务暂时失败 errorType={}", failure.getClass().getSimpleName());
                } finally { running.set(false); }
            });
        } catch (RuntimeException rejected) { running.set(false); }
    }

    public void tick() {
        if (!enabled) return;
        var jobs = jdbc.queryForList("""
            select id,exam_id from regrade_job where status='RUNNING'
            and (lease_until is null or lease_until<=current_timestamp(3)) order by created_at limit 10
            """);
        for (var job : jobs) {
            long id = number(job, "id");
            long exam = number(job, "exam_id");
            String token = UUID.randomUUID().toString();
            int claimed = jdbc.update("""
                update regrade_job set lease_token=?,lease_until=timestampadd(second,60,current_timestamp(3))
                where id=? and status='RUNNING' and (lease_until is null or lease_until<=current_timestamp(3))
                """, token, id);
            if (claimed == 0) continue;
            try { runBatch(exam, id, token); }
            catch (RuntimeException failure) {
                LOG.warn("重判任务暂时失败 jobId={} errorType={}", id, failure.getClass().getSimpleName());
                jdbc.update("""
                    update regrade_job set scan_attempts=scan_attempts+1,
                        status=case when scan_attempts>=5 then 'FAILED' else status end,last_error='读取交卷或处理任务失败，请重试'
                    where id=? and lease_token=? and lease_until>current_timestamp(3)
                    """, id, token);
            } finally {
                jdbc.update("update regrade_job set lease_token=null,lease_until=null where id=? and lease_token=?", id, token);
            }
        }
        syncBank();
        syncProjection();
    }

    private void runBatch(long exam, long id, String token) {
        var job = jdbc.queryForMap("select * from regrade_job where id=?", id);
        if (!Boolean.TRUE.equals(job.get("scan_complete")) && number(job, "scan_complete") == 0) {
            var page = runtime.page(exam, number(job, "cursor_id"), number(job, "upper_bound")).getData();
            if (page == null) throw new IllegalStateException("交卷分页不可用");
            tx.executeWithoutResult(status -> {
                if (!fence(exam, id, token)) return;
                for (Long sid : page.submissionIds()) jdbc.update("insert ignore into regrade_item(job_id,submission_id) values(?,?)", id, sid);
                long cursor = page.submissionIds().isEmpty() ? number(job, "cursor_id") : page.submissionIds().getLast();
                jdbc.update("update regrade_job set cursor_id=?,scan_complete=?,total=greatest(total,(select count(*) from regrade_item where job_id=?)),scan_attempts=0,last_error=null where id=?",
                    cursor, page.submissionIds().size() < 100, id, id);
            });
        }
        var items = jdbc.queryForList("""
            select submission_id from regrade_item where job_id=? and status<>'DONE' and attempts<5 order by submission_id limit 100
            """, Long.class, id);
        for (Long sid : items) {
            try {
                var input = inputs.input(sid).getData();
                if (input == null || !Objects.equals(input.examId(), exam) || !Objects.equals(input.submissionId(), sid)) {
                    throw new IllegalStateException("交卷记录不属于本次重判");
                }
                var paper = snapshots.snapshot(input.paperSnapshotId()).getData();
                tx.executeWithoutResult(status -> {
                    if (!fence(exam, id, token)) return;
                    var item = jdbc.queryForMap("select * from regrade_item where job_id=? and submission_id=? for update", id, sid);
                    if ("DONE".equals(item.get("status"))) return;
                    // Same row lock as subjective marking: audit and total must observe one consistent state.
                    jdbc.queryForList("select id from grading_submission where runtime_submission_id=? for update", sid);
                    var before = scores(sid);
                    grading.regrade(input, paper, number(job, "answer_version"));
                    var after = scores(sid);
                    jdbc.update("""
                        update regrade_item set status='DONE',attempts=attempts+1,student_id=?,before_json=?,after_json=?,
                            grade_revision=?,grade_status=?,last_error=null where job_id=? and submission_id=?
                        """, input.studentId(), keys.json(before), keys.json(after), after.get("grade_revision"), after.get("status"), id, sid);
                });
            } catch (RuntimeException failure) {
                LOG.warn("重判试卷失败 jobId={} submissionId={} errorType={}", id, sid, failure.getClass().getSimpleName());
                tx.executeWithoutResult(status -> {
                    if (fence(exam, id, token)) jdbc.update("""
                        update regrade_item set status='FAILED',attempts=attempts+1,last_error='读取作答或判分失败，请重试'
                        where job_id=? and submission_id=? and status<>'DONE'
                        """, id, sid);
                });
            }
        }
        tx.executeWithoutResult(status -> {
            if (!fence(exam, id, token)) return;
            int remaining = jdbc.queryForObject("select count(*) from regrade_item where job_id=? and status<>'DONE' and attempts<5", Integer.class, id);
            int failed = jdbc.queryForObject("select count(*) from regrade_item where job_id=? and status='FAILED'", Integer.class, id);
            jdbc.update("""
                update regrade_job set status=case when scan_complete=1 and ?=0 then ? else status end where id=?
                """, remaining, failed == 0 ? "COMPLETED" : "FAILED", id);
        });
    }
    private Map<String, Object> scores(Long sid) {
        var rows = jdbc.queryForList("""
            select objective_score,subjective_score,total_score,pass_flag,status,answer_version,grade_revision
            from grade_result where runtime_submission_id=?
            """, sid);
        if (rows.isEmpty()) return Map.of();
        var result = new LinkedHashMap<>(rows.getFirst());
        result.put("questionResults", jdbc.queryForList("""
            select cast(question_id as char) question_id,correct_flag,earned_score,max_score from grading_answer_result
            where runtime_submission_id=? and objective_flag=1 order by question_id
            """, sid));
        return result;
    }
    private boolean fence(long exam, long id, String token) {
        keys.lock(exam);
        var rows = jdbc.queryForList("""
            select id from regrade_job where id=? and status='RUNNING' and lease_token=?
                and lease_until>current_timestamp(3) for update
            """, id, token);
        if (rows.isEmpty()) return false;
        jdbc.update("update regrade_job set lease_until=timestampadd(second,60,current_timestamp(3)) where id=?", id);
        return true;
    }
    private void syncBank() {
        var rows = jdbc.queryForList("""
            select id,exam_id from regrade_bank_sync where status='PENDING'
            and next_retry_at<=current_timestamp(3) order by next_retry_at,id limit 100
            """);
        for (var candidate : rows) {
            long id = number(candidate, "id");
            try {
                // Durable dispatch marker survives a timeout or process crash. New versions wait for an uncertain write.
                boolean dispatched = Boolean.TRUE.equals(tx.execute(status -> {
                    keys.lock(number(candidate, "exam_id"));
                    return jdbc.update("""
                        update regrade_bank_sync set attempts=attempts+1,next_retry_at=timestampadd(second,60,current_timestamp(3))
                        where id=? and status='PENDING' and next_retry_at<=current_timestamp(3)
                        """, id) == 1;
                }));
                if (!dispatched) continue;
                tx.executeWithoutResult(status -> {
                    long current = keys.lock(number(candidate, "exam_id"));
                    var row = jdbc.queryForMap("select * from regrade_bank_sync where id=? for update", id);
                    if (!"PENDING".equals(row.get("status"))) return;
                    if (current != number(row, "answer_version")) {
                        jdbc.update("update regrade_bank_sync set status='SUPERSEDED' where id=?", id); return;
                    }
                    // Hold exam and sync locks until the remote response: activation cannot overtake an old sync.
                    var command = new QuestionCorrectionCommand((String) row.get("operation_id"), number(row, "operator_id"),
                        truth(row.get("administrator")), (String) row.get("expected_fingerprint"), (String) row.get("answer"));
                    var response = bank.correct(number(row, "question_id"), command);
                    if (response == null || !response.isSuccess() || response.getData() == null) throw new IllegalStateException("题库同步不可用");
                    var result = response.getData();
                    jdbc.update("update regrade_bank_sync set status=?,last_error=? where id=?",
                        result.status(), "APPLIED".equals(result.status()) ? null : result.message(), id);
                });
            } catch (RuntimeException failure) {
                // Preserve operationId on transport failures, including a lost successful response.
                jdbc.update("""
                    update regrade_bank_sync set status=case when attempts>=5 then 'FAILED' else 'PENDING' end,
                        next_retry_at=timestampadd(second,30,current_timestamp(3)),last_error='题库同步暂时失败，请稍后重试'
                    where id=? and status='PENDING'
                    """, id);
            }
        }
    }
    private void syncProjection() {
        // A subjective task may finish after the regrade job itself completes.
        jdbc.update("""
            update regrade_item i join grade_result g on g.runtime_submission_id=i.submission_id
            set i.grade_status='GRADED',i.grade_revision=g.grade_revision
            where i.status='DONE' and i.grade_status<>'GRADED' and g.status='GRADED'
            """);
        var jobs = jdbc.queryForList("""
            select distinct j.id,j.exam_id from regrade_job j join regrade_item i on i.job_id=j.id
            where i.status='DONE' and i.grade_status='GRADED' and i.projection_synced=0 order by j.id limit 10
            """);
        for (var job : jobs) {
            long id = number(job, "id");
            var revisions = jdbc.query("""
                select submission_id,grade_revision from regrade_item where job_id=? and status='DONE'
                and grade_status='GRADED' and projection_synced=0 order by submission_id limit 100
                """, (rs, n) -> new GradeProjectionQuery.Revision(rs.getLong(1), rs.getLong(2)), id);
            try {
                var progress = reporting.progress(new GradeProjectionQuery(number(job, "exam_id"), revisions)).getData();
                if (progress == null) continue;
                for (var revision : revisions) if (progress.synchronizedSubmissionIds().contains(revision.submissionId())) {
                    jdbc.update("update regrade_item set projection_synced=1 where job_id=? and submission_id=? and grade_revision=?",
                        id, revision.submissionId(), revision.gradeRevision());
                }
            } catch (RuntimeException unavailable) {
                LOG.debug("等待成绩投影同步 jobId={}", id);
            }
        }
    }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0; }
    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Boolean b) return b ? 1 : 0;
        return ((Number) value).longValue();
    }
}
