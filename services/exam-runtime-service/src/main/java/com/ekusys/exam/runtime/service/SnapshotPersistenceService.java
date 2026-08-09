package com.ekusys.exam.runtime.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.AnswerPayload;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnapshotPersistenceService {
    private final JdbcTemplate jdbc;

    public SnapshotPersistenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SnapshotDraft loadDraft(Long examId, Long studentId) {
        SubmissionDraftMetadata metadata = loadDraftMetadata(examId, studentId);
        return loadDraft(metadata);
    }

    public SubmissionDraftMetadata loadDraftMetadata(Long examId, Long studentId) {
        List<SubmissionDraftRow> submissions = jdbc.query(
            """
                select id,draft_version
                  from submission
                 where exam_id=? and student_id=? and status='IN_PROGRESS'
                 limit 1
                """,
            (rs, rowNum) -> new SubmissionDraftRow(rs.getLong("id"), rs.getLong("draft_version")),
            examId, studentId
        );
        if (submissions.isEmpty()) {
            return SubmissionDraftMetadata.empty();
        }
        SubmissionDraftRow submission = submissions.getFirst();
        return new SubmissionDraftMetadata(submission.id(), submission.version(), true);
    }

    public SnapshotDraft loadDraft(SubmissionDraftMetadata metadata) {
        if (metadata == null || !metadata.exists()) {
            return SnapshotDraft.empty();
        }
        Map<Long, String> answers = new LinkedHashMap<>();
        List<LocalDateTime> updatedTimes = new java.util.ArrayList<>();
        jdbc.query(
            "select question_id,answer_text,update_time from submission_answer where submission_id=? order by question_id",
            rs -> {
                String answerText = rs.getString("answer_text");
                answers.put(rs.getLong("question_id"), answerText == null ? "" : answerText);
                Timestamp updatedAt = rs.getTimestamp("update_time");
                if (updatedAt != null) {
                    updatedTimes.add(updatedAt.toLocalDateTime());
                }
            },
            metadata.submissionId()
        );
        LocalDateTime updatedAt = updatedTimes.stream().max(LocalDateTime::compareTo).orElse(null);
        return new SnapshotDraft(Map.copyOf(answers), metadata.version(), updatedAt);
    }

    @Transactional
    public long persistFallback(Long sessionId, Long examId, Long studentId,
                                List<AnswerPayload> answers, long version, LocalDateTime receivedAt) {
        if (touchActiveSession(sessionId, receivedAt) != 1) {
            throw new BusinessException("考试会话已结束");
        }
        return persistDraftInternal(examId, studentId, answers, version);
    }

    @Transactional
    public long persistDraft(Long examId, Long studentId, List<AnswerPayload> answers, long version) {
        return persistDraftInternal(examId, studentId, answers, version);
    }

    @Transactional
    public long persistDraft(Long examId, Long studentId, List<AnswerPayload> answers,
                             long version, LocalDateTime receivedAt) {
        long storedVersion = persistDraftInternal(examId, studentId, answers, version);
        if (storedVersion >= version && receivedAt != null) {
            jdbc.update(
                """
                    update exam_session
                       set last_snapshot_time=greatest(coalesce(last_snapshot_time,?),?),update_time=?
                     where exam_id=? and student_id=? and status='ANSWERING'
                    """,
                receivedAt, receivedAt, receivedAt, examId, studentId
            );
        }
        return storedVersion;
    }

    public int touchActiveSession(Long sessionId, LocalDateTime receivedAt) {
        return jdbc.update(
            """
                update exam_session
                   set last_snapshot_time=?,update_time=?
                 where id=? and status='ANSWERING' and deadline_time>current_timestamp(3)
                """,
            receivedAt, receivedAt, sessionId
        );
    }

    public void replaceFinalAnswers(Long submissionId, Map<Long, String> answers, String source) {
        jdbc.update("delete from submission_answer where submission_id=?", submissionId);
        answers.forEach((questionId, answerText) -> jdbc.update(
            """
                insert into submission_answer(
                    id,submission_id,question_id,answer_text,final_answer,source,create_time,update_time
                ) values(?,?,?,?,1,?,current_timestamp(3),current_timestamp(3))
                """,
            IdWorker.getId(), submissionId, questionId, answerText == null ? "" : answerText, source
        ));
    }

    private long persistDraftInternal(Long examId, Long studentId,
                                      List<AnswerPayload> answers, long version) {
        int claimed = jdbc.update(
            """
                update submission
                   set draft_version=?,update_time=current_timestamp(3)
                 where exam_id=? and student_id=? and status='IN_PROGRESS' and draft_version<?
                """,
            version, examId, studentId, version
        );
        if (claimed == 0) {
            List<Long> versions = jdbc.queryForList(
                "select draft_version from submission where exam_id=? and student_id=? and status='IN_PROGRESS'",
                Long.class, examId, studentId
            );
            return versions.isEmpty() ? -1L : versions.getFirst();
        }

        Long submissionId = jdbc.queryForObject(
            "select id from submission where exam_id=? and student_id=? and status='IN_PROGRESS'",
            Long.class, examId, studentId
        );
        jdbc.update(
            "delete from submission_answer where submission_id=? and (final_answer=0 or final_answer is null)",
            submissionId
        );
        for (AnswerPayload answer : answers) {
            jdbc.update(
                """
                    insert into submission_answer(
                        id,submission_id,question_id,answer_text,final_answer,source,create_time,update_time
                    ) values(?,?,?,?,0,'SNAPSHOT',current_timestamp(3),current_timestamp(3))
                    """,
                IdWorker.getId(), submissionId, answer.getQuestionId(),
                answer.getAnswerText() == null ? "" : answer.getAnswerText()
            );
        }
        return version;
    }

    private record SubmissionDraftRow(Long id, long version) {
    }

    public record SubmissionDraftMetadata(Long submissionId, long version, boolean exists) {
        public static SubmissionDraftMetadata empty() {
            return new SubmissionDraftMetadata(null, 0L, false);
        }
    }
}
