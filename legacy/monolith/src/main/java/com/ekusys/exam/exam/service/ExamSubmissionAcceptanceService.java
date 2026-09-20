package com.ekusys.exam.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.enums.SubmissionStatus;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamSession;
import com.ekusys.exam.repository.entity.PaperQuestion;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.entity.SubmissionAnswer;
import com.ekusys.exam.repository.mapper.PaperQuestionMapper;
import com.ekusys.exam.repository.mapper.SubmissionAnswerMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamSubmissionAcceptanceService {

    private final ExamSessionService examSessionService;
    private final ExamSnapshotService examSnapshotService;
    private final SubmissionMapper submissionMapper;
    private final SubmissionAnswerMapper submissionAnswerMapper;
    private final PaperQuestionMapper paperQuestionMapper;

    public ExamSubmissionAcceptanceService(ExamSessionService examSessionService,
                                           ExamSnapshotService examSnapshotService,
                                           SubmissionMapper submissionMapper,
                                           SubmissionAnswerMapper submissionAnswerMapper,
                                           PaperQuestionMapper paperQuestionMapper) {
        this.examSessionService = examSessionService;
        this.examSnapshotService = examSnapshotService;
        this.submissionMapper = submissionMapper;
        this.submissionAnswerMapper = submissionAnswerMapper;
        this.paperQuestionMapper = paperQuestionMapper;
    }

    @Transactional
    public ExamSubmissionAcceptedContext acceptSubmission(Exam exam,
                                                          ExamSession session,
                                                          Long studentId,
                                                          Map<Long, String> answerMap,
                                                          LocalDateTime submittedAt,
                                                          boolean timeoutSubmit) {
        Submission submission = examSessionService.ensureInProgressSubmission(exam.getId(), studentId);
        List<PaperQuestion> paperQuestions = paperQuestionMapper.selectList(
            new LambdaQueryWrapper<PaperQuestion>()
                .eq(PaperQuestion::getPaperId, exam.getPaperId())
                .orderByAsc(PaperQuestion::getSortOrder)
        );

        submissionAnswerMapper.delete(new LambdaQueryWrapper<SubmissionAnswer>()
            .eq(SubmissionAnswer::getSubmissionId, submission.getId()));

        for (PaperQuestion link : paperQuestions) {
            SubmissionAnswer item = new SubmissionAnswer();
            item.setSubmissionId(submission.getId());
            item.setQuestionId(link.getQuestionId());
            item.setAnswerText(answerMap.getOrDefault(link.getQuestionId(), ""));
            item.setFinalAnswer(true);
            item.setSource(timeoutSubmit ? "TIMEOUT_SUBMIT" : "SUBMIT");
            item.setObjectiveCorrect(null);
            item.setObjectiveScore(null);
            item.setSubjectiveScore(null);
            submissionAnswerMapper.insert(item);
        }

        submission.setObjectiveScore(null);
        submission.setSubjectiveScore(null);
        submission.setTotalScore(null);
        submission.setPassFlag(null);
        submission.setSubmittedAt(submittedAt);
        submission.setStatus(SubmissionStatus.PROCESSING.name());
        submissionMapper.updateById(submission);

        if (timeoutSubmit) {
            examSessionService.markTimeout(session, submittedAt);
        } else {
            examSessionService.markSubmitted(session, submittedAt);
        }
        examSnapshotService.clearSnapshot(exam.getId(), studentId);

        return new ExamSubmissionAcceptedContext(
            submission.getId(),
            exam.getId(),
            studentId,
            submittedAt,
            timeoutSubmit
        );
    }
}
