package com.ekusys.exam.grading.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.common.util.AnswerJudgeUtil;
import com.ekusys.exam.content.api.*;
import com.ekusys.exam.grading.client.*;
import com.ekusys.exam.grading.dto.RegradeRequest;
import com.ekusys.exam.management.api.ExamRegradeContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RegradeService {
    private final JdbcTemplate jdbc;
    private final AnswerKeyService keys;
    private final ContentGradingClient snapshots;
    private final RegradeClients.Management management;
    private final RegradeClients.Runtime runtime;
    private final RegradeClients.Content bank;
    private final TransactionTemplate tx;
    @Value("${app.regrading.accepting-new-jobs:true}")
    private boolean accepting = true;

    public RegradeService(JdbcTemplate jdbc, AnswerKeyService keys, ContentGradingClient snapshots,
            RegradeClients.Management management, RegradeClients.Runtime runtime, RegradeClients.Content bank,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.keys = keys; this.snapshots = snapshots; this.management = management;
        this.runtime = runtime; this.bank = bank;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public ExamRegradeContext authorize(Long examId) {
        var context = management.context(examId).getData();
        if (context == null || (!admin() && !Objects.equals(context.publisherId(), SecurityUtils.getCurrentUserId()))) {
            throw new BusinessException("无权限管理此考试");
        }
        if (SecurityUtils.getCurrentUserId() == null) throw new BusinessException("请先登录");
        return context;
    }
    private boolean admin() { return SecurityUtils.getCurrentRoles().contains("ADMIN"); }
    private void requireEnded(ExamRegradeContext context) {
        if ("DRAFT".equals(context.status()) || (!"TERMINATED".equals(context.status())
            && (context.endTime() == null || context.endTime().isAfter(LocalDateTime.now())))) {
            throw new BusinessException("仅允许对已结束或已终止考试重新判分");
        }
    }
    public Map<String, Object> answerKey(Long examId) {
        var context = authorize(examId);
        requireEnded(context);
        var paper = snapshots.snapshot(context.paperSnapshotId()).getData();
        return tx.execute(status -> {
            long version = keys.lock(examId);
            keys.baseline(examId, paper);
            var result = new LinkedHashMap<String, Object>();
            result.put("version", version);
            result.put("snapshot", paper);
            result.put("answers", keys.answers(examId, version));
            result.put("accepting", accepting);
            result.put("activeJob", jdbc.queryForObject("select count(*) from regrade_job where exam_id=? and status<>'COMPLETED'", Integer.class, examId) > 0);
            return result;
        });
    }
    public Long create(Long examId, RegradeRequest request, Long restoreVersion) {
        var context = authorize(examId);
        requireEnded(context);
        String requestHash = hash(keys.json(Arrays.asList(request.expectedVersion(), request.reason(),
            new TreeMap<>(request.answers()), restoreVersion)));
        var existing = existing(examId, request.requestKey(), requestHash);
        if (existing != null) return existing;
        if (!accepting) throw new BusinessException("重判入口暂时关闭，请稍后重试");
        var paper = snapshots.snapshot(context.paperSnapshotId()).getData();
        Map<Long, String> changes = new TreeMap<>();
        if (restoreVersion == null) {
            if (request.answers().isEmpty()) throw new BusinessException("请至少修改一道客观题答案");
            for (var entry : request.answers().entrySet()) {
                var q = paper.questions().stream().filter(v -> v.questionId().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> new BusinessException("题目不属于本场考试"));
                changes.put(q.questionId(), keys.validate(q, entry.getValue()));
            }
        }
        var page = runtime.page(examId, 0, null).getData();
        if (page == null) throw new BusinessException("无法读取考试交卷范围");
        Map<Long, QuestionCorrectionView> bankViews = new HashMap<>();
        if (restoreVersion == null) for (Long id : changes.keySet()) {
            try { bankViews.put(id, bank.context(id).getData()); }
            catch (RuntimeException unavailable) { /* Persist a visible conflict; never block score correction. */ }
        }
        long operator = SecurityUtils.getCurrentUserId();
        boolean administrator = admin();
        return tx.execute(status -> {
            long current = keys.lock(examId);
            Long duplicate = existing(examId, request.requestKey(), requestHash);
            if (duplicate != null) return duplicate;
            if (current != request.expectedVersion()) throw new BusinessException("答案版本已变化，请刷新后重试");
            if (jdbc.queryForObject("select count(*) from regrade_job where exam_id=? and status<>'COMPLETED'", Integer.class, examId) > 0) {
                throw new BusinessException("本场考试仍有未完成任务，请先完成或重试失败任务");
            }
            // An uncertain remote write must be resolved with its original idempotency key before a later version can supersede it.
            if (jdbc.queryForObject("select count(*) from regrade_bank_sync where exam_id=? and status in ('PENDING','FAILED') and attempts>0",
                    Integer.class, examId) > 0) throw new BusinessException("题库同步结果尚未确认，请先重试题库同步后创建新版本");
            keys.baseline(examId, paper);
            var effective = new TreeMap<>(restoreVersion == null ? keys.answers(examId, current) : keys.answers(examId, restoreVersion));
            if (restoreVersion == null) effective.putAll(changes);
            if (effective.equals(keys.answers(examId, current))) throw new BusinessException("答案未发生变化，无需重判");
            long version = current + 1;
            long job = IdWorker.getId();
            jdbc.update("""
                insert into grading_key_version(exam_id,version,parent_version,snapshot_id,operator_id,reason,answers_json,restored_from_version)
                values(?,?,?,?,?,?,?,?)
                """, examId, version, current, context.paperSnapshotId(), operator,
                request.reason(), keys.json(effective), restoreVersion);
            jdbc.update("update grading_exam_key set current_version=? where exam_id=?", version, examId);
            jdbc.update("""
                insert into regrade_job(id,exam_id,answer_version,request_key,request_hash,upper_bound,total)
                values(?,?,?,?,?,?,?)
                """, job, examId, version, request.requestKey(), requestHash, page.upperBound(), page.total());
            jdbc.update("""
                update regrade_bank_sync set status='SUPERSEDED',last_error='已由新答案版本取代'
                where exam_id=? and status<>'APPLIED'
                """, examId);
            if (restoreVersion == null) for (var entry : changes.entrySet()) {
                var q = paper.questions().stream().filter(v -> v.questionId().equals(entry.getKey())).findFirst().orElseThrow();
                var view = bankViews.get(entry.getKey());
                boolean compatible = sameQuestion(q, view);
                jdbc.update("""
                    insert into regrade_bank_sync(id,job_id,exam_id,answer_version,question_id,answer,expected_fingerprint,
                        operation_id,operator_id,administrator,status,last_error)
                    values(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, IdWorker.getId(), job, examId, version, entry.getKey(), entry.getValue(),
                    compatible ? view.fingerprint() : null, UUID.randomUUID().toString(), operator, administrator,
                    compatible ? "PENDING" : "CONFLICT", compatible ? null : "无法读取题库或题干/选项已变化，请核对后重试");
            }
            return job;
        });
    }
    private Long existing(Long examId, String requestKey, String requestHash) {
        var rows = jdbc.queryForList("select id,request_hash from regrade_job where exam_id=? and request_key=?", examId, requestKey);
        if (rows.isEmpty()) return null;
        if (!requestHash.equals(rows.getFirst().get("request_hash"))) throw new BusinessException("请求标识已用于其他重判内容");
        return ((Number) rows.getFirst().get("id")).longValue();
    }
    static boolean sameQuestion(PaperSnapshotQuestion q, QuestionCorrectionView view) {
        return view != null && Objects.equals(q.type(), view.type()) && Objects.equals(q.content(), view.content())
            && Objects.equals(q.optionsJson(), view.optionsJson());
    }
    public List<Map<String, Object>> history(Long examId, int page) {
        authorize(examId);
        return jdbc.queryForList("""
            select j.id,j.answer_version,j.status,j.total,j.created_at,v.parent_version,v.restored_from_version,v.operator_id,v.reason,v.answers_json,p.answers_json previous_answers_json
            from regrade_job j join grading_key_version v on v.exam_id=j.exam_id and v.version=j.answer_version
            left join grading_key_version p on p.exam_id=v.exam_id and p.version=v.parent_version
            where j.exam_id=? order by j.answer_version desc limit 20 offset ?
            """, examId, offset(page, 20));
    }
    public Map<String, Object> detail(Long examId, Long jobId, int page) {
        authorize(examId);
        var job = job(examId, jobId);
        var result = new LinkedHashMap<>(job);
        result.put("counts", jdbc.queryForList("select status,count(*) total from regrade_item where job_id=? group by status", jobId));
        result.put("items", jdbc.queryForList("""
            select submission_id,student_id,status,attempts,before_json,after_json,last_error,grade_status,projection_synced
            from regrade_item where job_id=? order by submission_id limit 50 offset ?
            """, jobId, offset(page, 50)));
        result.put("itemTotal", jdbc.queryForObject("select count(*) from regrade_item where job_id=?", Long.class, jobId));
        result.put("projectionPending", jdbc.queryForObject("""
            select count(*) from regrade_item where job_id=? and status='DONE' and grade_status='GRADED' and projection_synced=0
            """, Long.class, jobId));
        result.put("subjectivePending", jdbc.queryForObject("""
            select count(*) from regrade_item where job_id=? and status='DONE' and grade_status<>'GRADED'
            """, Long.class, jobId));
        result.put("bankSync", jdbc.queryForList("""
            select id,question_id,answer,status,attempts,last_error from regrade_bank_sync where job_id=? order by question_id
            """, jobId));
        return result;
    }
    private int offset(int page, int size) {
        if (page < 1 || page > 1000000) throw new BusinessException("无效页码");
        return (page - 1) * size;
    }
    private Map<String, Object> job(Long examId, Long jobId) {
        var rows = jdbc.queryForList("""
            select id,exam_id,answer_version,status,total,scan_complete,last_error,created_at from regrade_job where id=? and exam_id=?
            """, jobId, examId);
        if (rows.isEmpty()) throw new BusinessException("重判任务不存在");
        return rows.getFirst();
    }
    public void retry(Long examId, Long jobId) {
        authorize(examId);
        tx.executeWithoutResult(status -> {
            long version = keys.lock(examId);
            var job = job(examId, jobId);
            if (version != ((Number) job.get("answer_version")).longValue()) throw new BusinessException("不能重试历史版本任务");
            if (!"FAILED".equals(job.get("status"))) throw new BusinessException("仅失败任务可以重试");
            jdbc.update("update regrade_item set status='PENDING',attempts=0,last_error=null where job_id=? and status='FAILED'", jobId);
            jdbc.update("update regrade_job set status='RUNNING',scan_attempts=0,lease_token=null,lease_until=null,last_error=null where id=?", jobId);
        });
    }
    public Map<String, Object> bankContext(Long examId, Long jobId, Long syncId) {
        authorize(examId); job(examId, jobId);
        var row = sync(jobId, syncId);
        var view = bank.context(((Number) row.get("question_id")).longValue()).getData();
        var result = new LinkedHashMap<String, Object>();
        result.put("question", view); result.put("targetAnswer", row.get("answer"));
        return result;
    }
    public void retryBank(Long examId, Long jobId, Long syncId, String fingerprint) {
        var context = authorize(examId);
        job(examId, jobId);
        var row = sync(jobId, syncId);
        if ("FAILED".equals(row.get("status"))) {
            tx.executeWithoutResult(status -> {
                long version = keys.lock(examId);
                if (version != ((Number) row.get("answer_version")).longValue()) throw new BusinessException("历史版本题库同步已失效");
                jdbc.update("update regrade_bank_sync set status='PENDING',next_retry_at=current_timestamp(3) where id=? and status='FAILED'", syncId);
            });
            return;
        }
        var paper = snapshots.snapshot(context.paperSnapshotId()).getData();
        long questionId = ((Number) row.get("question_id")).longValue();
        var view = bank.context(questionId).getData();
        if (view == null || !Objects.equals(fingerprint, view.fingerprint())) throw new BusinessException("题库版本已变化，请重新核对");
        if (!admin() && !Objects.equals(view.creatorId(), SecurityUtils.getCurrentUserId())) throw new BusinessException("无权限修改题库题目");
        var q = paper.questions().stream().filter(v -> v.questionId().equals(questionId)).findFirst().orElseThrow();
        if (!sameQuestion(q, view)) throw new BusinessException("题干或选项已不同，请在题库管理中修正，不能复用旧试卷答案");
        tx.executeWithoutResult(status -> {
            long version = keys.lock(examId);
            job(examId, jobId);
            if (version != ((Number) row.get("answer_version")).longValue()) throw new BusinessException("历史版本题库同步已失效");
            jdbc.update("""
                update regrade_bank_sync set status='PENDING',attempts=0,last_error=null,expected_fingerprint=?,
                    operation_id=?,operator_id=?,administrator=?,next_retry_at=current_timestamp(3)
                where id=? and status in ('CONFLICT','FORBIDDEN','DELETED','FAILED')
                """, fingerprint, UUID.randomUUID().toString(), SecurityUtils.getCurrentUserId(), admin(), syncId);
        });
    }
    private Map<String, Object> sync(Long jobId, Long syncId) {
        var rows = jdbc.queryForList("select * from regrade_bank_sync where id=? and job_id=?", syncId, jobId);
        if (rows.isEmpty()) throw new BusinessException("题库同步记录不存在");
        return rows.getFirst();
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
