package com.ekusys.exam.grading.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.common.util.AnswerJudgeUtil;
import com.ekusys.exam.content.api.PaperSnapshotQuestion;
import com.ekusys.exam.grading.client.ContentGradingClient;
import com.ekusys.exam.grading.client.RuntimeGradingClient;
import com.ekusys.exam.grading.dto.*;
import com.ekusys.exam.grading.messaging.GradingOutboxService;
import com.ekusys.exam.runtime.api.GradingAnswerInput;
import com.ekusys.exam.runtime.api.GradingSubmissionInput;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class GradingService {
    private final JdbcTemplate jdbc;
    private final RuntimeGradingClient runtime;
    private final ContentGradingClient content;
    private final GradingOutboxService outbox;
    private final AnswerKeyService keys;

    public GradingService(JdbcTemplate jdbc, RuntimeGradingClient runtime, ContentGradingClient content,
                          GradingOutboxService outbox, AnswerKeyService keys) {
        this.jdbc = jdbc; this.runtime = runtime; this.content = content; this.outbox = outbox; this.keys = keys;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void processSubmission(Long id) {
        var input = runtime.input(id).getData();
        var paper = content.snapshot(input.paperSnapshotId()).getData();
        processInput(input, paper);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void processInput(GradingSubmissionInput input, com.ekusys.exam.content.api.PaperSnapshotView paper) {
        long version = keys.lock(input.examId());
        if (exists(input.submissionId())) return;
        keys.baseline(input.examId(), paper);
        var effective = keys.answers(input.examId(), version);
        long id = input.submissionId();
        long gid = IdWorker.getId();
        jdbc.update("""
            insert into grading_submission(id,runtime_submission_id,exam_id,exam_name,pass_score,student_id,
                paper_snapshot_id,status,submitted_at,create_time,update_time)
            values(?,?,?,?,?,?,?,'PROCESSING',?,current_timestamp(3),current_timestamp(3))
            """, gid, id, input.examId(), input.examName(), input.passScore(), input.studentId(), input.paperSnapshotId(), input.submittedAt());
        var answers = input.answers().stream().collect(Collectors.toMap(GradingAnswerInput::questionId, Function.identity(), (a,b) -> b));
        int objective = 0;
        int pending = 0;
        for (var q : paper.questions()) {
            var a = answers.get(q.questionId());
            if (AnswerJudgeUtil.isObjectiveType(q.type())) {
                boolean correct = a != null && AnswerJudgeUtil.isCorrect(q.type(), effective.get(q.questionId()), a.answerText());
                if (correct) objective += q.score();
                jdbc.update("""
                    insert into grading_answer_result(id,runtime_submission_id,exam_id,student_id,question_id,
                        question_content,objective_flag,correct_flag,earned_score,max_score,create_time,update_time)
                    values(?,?,?,?,?,?,1,?,?,?,current_timestamp(3),current_timestamp(3))
                    """, IdWorker.getId(), id, input.examId(), input.studentId(), q.questionId(), q.content(), correct ? 1 : 0, correct ? q.score() : 0, q.score());
            } else {
                pending++;
                jdbc.update("""
                    insert into grading_task(id,grading_submission_id,exam_id,student_id,exam_name,question_id,
                        question_content,reference_answer,analysis,answer_text,submitted_at,sort_order,answer_id,
                        max_score,status,create_time,update_time)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',current_timestamp(3),current_timestamp(3))
                    """, IdWorker.getId(), gid, input.examId(), input.studentId(), input.examName(), q.questionId(),
                    q.content(), q.answer(), q.analysis(), a == null ? null : a.answerText(), input.submittedAt(),
                    q.sortOrder(), a == null ? IdWorker.getId() : a.answerId(), q.score());
            }
        }
        String status = pending == 0 ? "GRADED" : "PENDING_SUBJECTIVE";
        jdbc.update("""
            insert into grade_result(id,runtime_submission_id,objective_score,subjective_score,total_score,pass_flag,
                status,completed_at,create_time,update_time,answer_version,grade_revision)
            values(?,?,?,0,?,?,?,case when ?='GRADED' then current_timestamp(3) else null end,current_timestamp(3),current_timestamp(3),?,1)
            """, IdWorker.getId(), id, objective, objective, objective >= input.passScore() ? 1 : 0, status, status, version);
        jdbc.update("update grading_submission set status=?,update_time=current_timestamp(3) where id=?", status, gid);
        if ("GRADED".equals(status)) outbox.gradeCompleted(id);
    }

    // Called by the worker inside its fenced transaction, after acquiring the exam lock.
    public void regrade(GradingSubmissionInput input, com.ekusys.exam.content.api.PaperSnapshotView paper, long version) {
        processInput(input, paper);
        long sid = input.submissionId();
        lockSubmission(sid);
        long current = jdbc.queryForObject("select answer_version from grade_result where runtime_submission_id=?", Long.class, sid);
        if (current >= version) return;
        var effective = keys.answers(input.examId(), version);
        var answers = input.answers().stream().collect(Collectors.toMap(GradingAnswerInput::questionId, Function.identity(), (a,b) -> b));
        for (var q : paper.questions()) {
            if (!AnswerJudgeUtil.isObjectiveType(q.type())) continue;
            var a = answers.get(q.questionId());
            boolean correct = a != null && AnswerJudgeUtil.isCorrect(q.type(), effective.get(q.questionId()), a.answerText());
            int changed = jdbc.update("""
                update grading_answer_result set correct_flag=?,earned_score=?,update_time=current_timestamp(3)
                where runtime_submission_id=? and question_id=? and objective_flag=1
                """, correct ? 1 : 0, correct ? q.score() : 0, sid, q.questionId());
            if (changed != 1) throw new BusinessException("客观题判分记录不完整，请核对后重试");
        }
        jdbc.update("""
            update grade_result set objective_score=(select coalesce(sum(earned_score),0) from grading_answer_result
                where runtime_submission_id=? and objective_flag=1),answer_version=? where runtime_submission_id=?
            """, sid, version, sid);
        recalculate(sid);
    }
 public List<PendingAnswerView> pendingAnswers(){return jdbc.query("select * from grading_task where status='PENDING' order by submitted_at,id",(rs,n)->PendingAnswerView.builder().submissionId(runtimeSubmission(rs.getLong("grading_submission_id"))).submissionAnswerId(rs.getLong("answer_id")).examId(rs.getLong("exam_id")).examName(rs.getString("exam_name")).studentId(rs.getLong("student_id")).questionId(rs.getLong("question_id")).questionContent(rs.getString("question_content")).answerText(rs.getString("answer_text")).build());}
 public List<PendingQuestionGroupView> pendingQuestionGroups(){return jdbc.query("select exam_id,exam_name,question_id,max(question_content) question_content,max(reference_answer) reference_answer,max(analysis) analysis,max(max_score) max_score,max(sort_order) sort_order,count(*) cnt from grading_task where status='PENDING' group by exam_id,exam_name,question_id order by exam_id,sort_order",(rs,n)->PendingQuestionGroupView.builder().examId(rs.getLong("exam_id")).examName(rs.getString("exam_name")).questionId(rs.getLong("question_id")).questionContent(rs.getString("question_content")).referenceAnswer(rs.getString("reference_answer")).analysis(rs.getString("analysis")).defaultScore(rs.getInt("max_score")).sortOrder(rs.getInt("sort_order")).pendingCount(rs.getInt("cnt")).build());}
 public List<PendingQuestionAnswerView> pendingQuestionAnswers(Long questionId,Long examId){return jdbc.query("select * from grading_task where status='PENDING' and question_id=? and exam_id=? order by submitted_at,id",(rs,n)->PendingQuestionAnswerView.builder().submissionId(runtimeSubmission(rs.getLong("grading_submission_id"))).submissionAnswerId(rs.getLong("answer_id")).studentId(rs.getLong("student_id")).answerText(rs.getString("answer_text")).submittedAt(rs.getObject("submitted_at",java.time.LocalDateTime.class)).build(),questionId,examId);}
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void scoreSubjective(Long submissionId, SubjectiveScoreRequest request) {
        lockSubmission(submissionId);
        for (SubjectiveScoreItem item : request.getScores()) {
            score(item.getSubmissionAnswerId(), item.getScore(), item.getComment(), submissionId, item.getLeaseToken());
        }
        recalculate(submissionId);
    }

    // Mapping reads precede the locks; READ_COMMITTED keeps later totals fresh after waiting.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void scoreQuestionAnswers(Long questionId, QuestionBatchScoreRequest request) {
        // Lock submissions in stable order before reading totals or changing any task.
        Map<Long, Long> submissions = new TreeMap<>();
        for (Long answerId : request.getSubmissionAnswerIds()) {
            List<Long> ids = jdbc.queryForList("""
                select gs.runtime_submission_id from grading_task t
                join grading_submission gs on gs.id=t.grading_submission_id
                where t.answer_id=? and t.question_id=? and t.exam_id=?
                """, Long.class, answerId, questionId, request.getExamId());
            if (ids.size() != 1) throw new BusinessException("评分项不属于当前考试或题目");
            submissions.put(answerId, ids.getFirst());
        }
        submissions.values().stream().distinct().sorted().forEach(this::lockSubmission);
        for (Long answerId : request.getSubmissionAnswerIds()) {
            score(answerId, request.getScore(), request.getComment(), submissions.get(answerId),
                request.getLeaseTokens() == null ? null : request.getLeaseTokens().get(answerId));
        }
        submissions.values().stream().distinct().sorted().forEach(this::recalculate);
    }

    private void lockSubmission(Long submissionId) {
        jdbc.queryForObject("select id from grading_submission where runtime_submission_id=? for update",
            Long.class, submissionId);
    }

    private void score(Long answerId, int score, String comment, Long submissionId, String token) {
        if (token == null || token.isBlank() || SecurityUtils.getCurrentUserId() == null) {
            throw new BusinessException("请先认领答案再提交评分");
        }
        int changed = jdbc.update("""
            update grading_task t join grading_submission gs on gs.id=t.grading_submission_id
            set t.status='GRADED', t.lease_token=null, t.lease_expires_at=null, t.update_time=current_timestamp(3)
            where t.answer_id=? and gs.runtime_submission_id=? and t.status='PENDING'
                and t.assigned_teacher_id=? and t.lease_token=? and t.lease_expires_at>current_timestamp(3)
                and ?>=0 and ?<=t.max_score
            """, answerId, submissionId, SecurityUtils.getCurrentUserId(), token, score, score);
        if (changed != 1) throw new BusinessException("GRADING_LEASE_CONFLICT", "租约已失效、答案已批阅或分数超限，请刷新后重新认领");
        Long task = jdbc.queryForObject("select id from grading_task where answer_id=?", Long.class, answerId);
        jdbc.update("""
            insert into subjective_grade(id,grading_task_id,teacher_id,score,comment,graded_at,create_time,update_time)
            values(?,?,?,?,?,current_timestamp(3),current_timestamp(3),current_timestamp(3))
            """, IdWorker.getId(), task, SecurityUtils.getCurrentUserId(), score, comment);
    }
    private void recalculate(Long sid) {
        int pending = jdbc.queryForObject("""
            select count(*) from grading_task t join grading_submission gs on gs.id=t.grading_submission_id
            where gs.runtime_submission_id=? and t.status='PENDING'
            """, Integer.class, sid);
        int subjective = jdbc.queryForObject("""
            select coalesce(sum(g.score),0) from subjective_grade g join grading_task t on t.id=g.grading_task_id
            join grading_submission gs on gs.id=t.grading_submission_id where gs.runtime_submission_id=?
            """, Integer.class, sid);
        int objective = jdbc.queryForObject("select objective_score from grade_result where runtime_submission_id=?", Integer.class, sid);
        int passScore = jdbc.queryForObject("select pass_score from grading_submission where runtime_submission_id=?", Integer.class, sid);
        int total = objective + subjective;
        String status = pending == 0 ? "GRADED" : "PENDING_SUBJECTIVE";
        jdbc.update("""
            update grade_result set grade_revision=grade_revision+1,subjective_score=?,total_score=?,pass_flag=?,status=?,
                completed_at=case when ?='GRADED' then current_timestamp(3) else null end,update_time=current_timestamp(3)
            where runtime_submission_id=?
            """, subjective, total, total >= passScore ? 1 : 0, status, status, sid);
        jdbc.update("update grading_submission set status=?,update_time=current_timestamp(3) where runtime_submission_id=?", status, sid);
        if ("GRADED".equals(status)) outbox.gradeCompleted(sid);
    }
 private boolean exists(Long id){Integer c=jdbc.queryForObject("select count(*) from grading_submission where runtime_submission_id=?",Integer.class,id);return c!=null&&c>0;}private Long runtimeSubmission(Long gid){return jdbc.queryForObject("select runtime_submission_id from grading_submission where id=?",Long.class,gid);}
}
