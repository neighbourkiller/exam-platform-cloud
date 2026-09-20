package com.ekusys.exam.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.enums.SessionStatus;
import com.ekusys.exam.common.enums.SubmissionStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.entity.ExamSession;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.mapper.ExamSessionMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ExamSessionService {

    private final ExamSessionMapper examSessionMapper;
    private final SubmissionMapper submissionMapper;

    public ExamSessionService(ExamSessionMapper examSessionMapper,
                              SubmissionMapper submissionMapper) {
        this.examSessionMapper = examSessionMapper;
        this.submissionMapper = submissionMapper;
    }

    public ExamSession startAnsweringSession(Long examId, Long studentId, LocalDateTime now, LocalDateTime deadlineTime) {
        ExamSession session = findLatestSession(examId, studentId);
        if (session != null && (SessionStatus.SUBMITTED.name().equals(session.getStatus())
            || SessionStatus.TIMEOUT.name().equals(session.getStatus()))) {
            throw new BusinessException("你已提交过本场考试");
        }
        if (session == null) {
            session = new ExamSession();
            session.setExamId(examId);
            session.setStudentId(studentId);
            session.setStatus(SessionStatus.ANSWERING.name());
            session.setStartTime(now);
            session.setDeadlineTime(deadlineTime);
            examSessionMapper.insert(session);
            return session;
        }
        if (isExpired(session, now)) {
            throw new BusinessException("考试作答时间已结束");
        }
        if (session.getStartTime() == null) {
            session.setStartTime(now);
        }
        if (session.getDeadlineTime() == null) {
            session.setDeadlineTime(deadlineTime);
        }
        session.setStatus(SessionStatus.ANSWERING.name());
        examSessionMapper.updateById(session);
        return session;
    }

    public ExamSession requireActiveSession(Long examId, Long studentId) {
        ExamSession session = findLatestSession(examId, studentId);
        if (session == null || SessionStatus.SUBMITTED.name().equals(session.getStatus())
            || SessionStatus.TIMEOUT.name().equals(session.getStatus())) {
            throw new BusinessException("会话已结束");
        }
        if (isExpired(session, LocalDateTime.now())) {
            throw new BusinessException("考试作答时间已结束");
        }
        return session;
    }

    public Submission ensureInProgressSubmission(Long examId, Long studentId) {
        Submission submission = findLatestSubmission(examId, studentId);
        if (submission == null) {
            submission = new Submission();
            submission.setExamId(examId);
            submission.setStudentId(studentId);
            submission.setStatus(SubmissionStatus.IN_PROGRESS.name());
            submission.setObjectiveScore(0);
            submission.setSubjectiveScore(0);
            submission.setTotalScore(0);
            submission.setPassFlag(false);
            submissionMapper.insert(submission);
        }
        return submission;
    }

    public Submission findLatestSubmission(Long examId, Long studentId) {
        return submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
            .eq(Submission::getExamId, examId)
            .eq(Submission::getStudentId, studentId)
            .last("limit 1"));
    }

    public ExamSession findLatestSession(Long examId, Long studentId) {
        return examSessionMapper.selectOne(new LambdaQueryWrapper<ExamSession>()
            .eq(ExamSession::getExamId, examId)
            .eq(ExamSession::getStudentId, studentId)
            .last("limit 1"));
    }

    public void touchSnapshot(ExamSession session, LocalDateTime snapshotTime) {
        session.setLastSnapshotTime(snapshotTime);
        examSessionMapper.updateById(session);
    }

    public void markSubmitted(ExamSession session, LocalDateTime submittedAt) {
        session.setStatus(SessionStatus.SUBMITTED.name());
        session.setEndTime(submittedAt);
        examSessionMapper.updateById(session);
    }

    public void markTimeout(ExamSession session, LocalDateTime timeoutAt) {
        session.setStatus(SessionStatus.TIMEOUT.name());
        session.setEndTime(timeoutAt);
        examSessionMapper.updateById(session);
    }

    public List<ExamSession> listExpiredAnsweringSessions(LocalDateTime now) {
        if (now == null) {
            return List.of();
        }
        return examSessionMapper.selectList(new LambdaQueryWrapper<ExamSession>()
            .eq(ExamSession::getStatus, SessionStatus.ANSWERING.name())
            .isNotNull(ExamSession::getDeadlineTime)
            .le(ExamSession::getDeadlineTime, now)
            .orderByAsc(ExamSession::getDeadlineTime, ExamSession::getId));
    }

    public boolean isExpired(ExamSession session, LocalDateTime now) {
        return session != null
            && session.getDeadlineTime() != null
            && now != null
            && !now.isBefore(session.getDeadlineTime());
    }
}
