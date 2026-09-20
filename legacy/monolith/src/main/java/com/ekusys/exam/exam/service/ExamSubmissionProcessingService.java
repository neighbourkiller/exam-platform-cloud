package com.ekusys.exam.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.enums.SubmissionStatus;
import com.ekusys.exam.common.util.AnswerJudgeUtil;
import com.ekusys.exam.exam.dto.SubmitResultView;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.PaperQuestion;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.entity.SubmissionAnswer;
import com.ekusys.exam.repository.mapper.PaperQuestionMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import com.ekusys.exam.repository.mapper.SubmissionAnswerMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamSubmissionProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ExamSubmissionProcessingService.class);

    private final ExamAccessService examAccessService;
    private final SubmissionMapper submissionMapper;
    private final SubmissionAnswerMapper submissionAnswerMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final QuestionMapper questionMapper;

    public ExamSubmissionProcessingService(ExamAccessService examAccessService,
                                           SubmissionMapper submissionMapper,
                                           SubmissionAnswerMapper submissionAnswerMapper,
                                           PaperQuestionMapper paperQuestionMapper,
                                           QuestionMapper questionMapper) {
        this.examAccessService = examAccessService;
        this.submissionMapper = submissionMapper;
        this.submissionAnswerMapper = submissionAnswerMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.questionMapper = questionMapper;
    }

    @Transactional
    public SubmitResultView processAcceptedSubmission(Long submissionId) {
        Submission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw new IllegalArgumentException("提交记录不存在: " + submissionId);
        }
        if (!SubmissionStatus.PROCESSING.name().equals(submission.getStatus())) {
            return toResult(submission);
        }

        Exam exam = examAccessService.ensureExam(submission.getExamId());
        List<PaperQuestion> paperQuestions = paperQuestionMapper.selectList(
            new LambdaQueryWrapper<PaperQuestion>()
                .eq(PaperQuestion::getPaperId, exam.getPaperId())
                .orderByAsc(PaperQuestion::getSortOrder)
        );
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(
            paperQuestions.stream().map(PaperQuestion::getQuestionId).toList()
        ).stream().collect(Collectors.toMap(Question::getId, Function.identity()));
        Map<Long, SubmissionAnswer> answerMap = submissionAnswerMapper.selectList(
            new LambdaQueryWrapper<SubmissionAnswer>().eq(SubmissionAnswer::getSubmissionId, submissionId)
        ).stream().collect(Collectors.toMap(SubmissionAnswer::getQuestionId, Function.identity(), (left, right) -> left));

        int objectiveScore = 0;
        boolean hasShort = false;

        for (PaperQuestion link : paperQuestions) {
            Question question = questionMap.get(link.getQuestionId());
            SubmissionAnswer answer = answerMap.get(link.getQuestionId());
            if (question == null || answer == null) {
                continue;
            }

            if (AnswerJudgeUtil.isObjectiveType(question.getType())) {
                boolean correct = AnswerJudgeUtil.isCorrect(question.getType(), question.getAnswer(), answer.getAnswerText());
                answer.setObjectiveCorrect(correct);
                answer.setObjectiveScore(correct ? link.getScore() : 0);
                submissionAnswerMapper.updateById(answer);
                objectiveScore += correct ? link.getScore() : 0;
            } else {
                hasShort = true;
                answer.setObjectiveCorrect(null);
                answer.setObjectiveScore(0);
                answer.setSubjectiveScore(null);
                submissionAnswerMapper.updateById(answer);
            }
        }

        submission.setObjectiveScore(objectiveScore);
        if (hasShort) {
            submission.setSubjectiveScore(null);
            submission.setTotalScore(null);
            submission.setPassFlag(null);
            submission.setStatus(SubmissionStatus.SUBMITTED.name());
        } else {
            submission.setSubjectiveScore(0);
            submission.setTotalScore(objectiveScore);
            submission.setPassFlag(objectiveScore >= exam.getPassScore());
            submission.setStatus(SubmissionStatus.GRADED.name());
        }
        submissionMapper.updateById(submission);

        log.info("Exam submission processed: submissionId={}, examId={}, studentId={}, status={}, objectiveScore={}",
            submission.getId(), submission.getExamId(), submission.getStudentId(), submission.getStatus(), submission.getObjectiveScore());
        return toResult(submission);
    }

    public SubmitResultView buildProcessingResult(Long submissionId) {
        return SubmitResultView.builder()
            .submissionId(submissionId)
            .objectiveScore(null)
            .subjectiveScore(null)
            .totalScore(null)
            .passFlag(null)
            .status(SubmissionStatus.PROCESSING.name())
            .build();
    }

    private SubmitResultView toResult(Submission submission) {
        return SubmitResultView.builder()
            .submissionId(submission.getId())
            .objectiveScore(submission.getObjectiveScore())
            .subjectiveScore(submission.getSubjectiveScore())
            .totalScore(submission.getTotalScore())
            .passFlag(submission.getPassFlag())
            .status(submission.getStatus())
            .build();
    }
}
