package com.ekusys.exam.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.service.ExamPermissionService;
import com.ekusys.exam.grading.dto.PendingAnswerView;
import com.ekusys.exam.grading.dto.PendingQuestionAnswerView;
import com.ekusys.exam.grading.dto.PendingQuestionGroupView;
import com.ekusys.exam.grading.dto.QuestionBatchScoreRequest;
import com.ekusys.exam.grading.dto.SubjectiveScoreItem;
import com.ekusys.exam.grading.dto.SubjectiveScoreRequest;
import com.ekusys.exam.grading.service.GradingService;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.PaperQuestion;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.entity.SubmissionAnswer;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.PaperQuestionMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import com.ekusys.exam.repository.mapper.SubmissionAnswerMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import com.ekusys.exam.repository.mapper.SubjectiveGradeMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GradingServiceTest {

    @Mock
    private SubmissionMapper submissionMapper;

    @Mock
    private SubmissionAnswerMapper submissionAnswerMapper;

    @Mock
    private PaperQuestionMapper paperQuestionMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;

    @Mock
    private ExamMapper examMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ExamTargetClassMapper examTargetClassMapper;

    @Mock
    private TeachingClassMapper teachingClassMapper;

    private GradingService gradingService;

    @BeforeEach
    void setUp() {
        ExamPermissionService examPermissionService = new ExamPermissionService(
            userMapper,
            examTargetClassMapper,
            teachingClassMapper
        );
        gradingService = new GradingService(
            submissionMapper,
            submissionAnswerMapper,
            paperQuestionMapper,
            questionMapper,
            subjectiveGradeMapper,
            examMapper,
            userMapper,
            examPermissionService
        );
    }

    @Test
    void pendingAnswersShouldOnlyReturnAccessibleExamRecords() {
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            submission(1L, 101L, 1001L),
            submission(2L, 102L, 1002L)
        ));
        when(examMapper.selectBatchIds(any())).thenReturn(List.of(
            exam(101L, 200L, 10001L, LocalDateTime.of(2026, 4, 7, 9, 0)),
            exam(102L, 999L, 10002L, LocalDateTime.of(2026, 4, 8, 9, 0))
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
            user(1001L, "Alice"),
            user(1002L, "Bob")
        ));
        when(submissionAnswerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            answer(11L, 1L, 501L, "答案A"),
            answer(12L, 2L, 502L, "答案B")
        ));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
            shortQuestion(501L, "题目A", 10),
            shortQuestion(502L, "题目B", 10)
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            List<PendingAnswerView> result = gradingService.pendingAnswers();

            assertEquals(1, result.size());
            assertEquals(101L, result.get(0).getExamId());
            assertEquals("Alice", result.get(0).getStudentName());
        }
    }

    @Test
    void pendingQuestionGroupsShouldSortByExamAndQuestionOrder() {
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            submission(1L, 101L, 1001L),
            submission(2L, 101L, 1002L),
            submission(3L, 102L, 1003L)
        ));
        when(examMapper.selectBatchIds(any())).thenReturn(List.of(
            exam(101L, 200L, 10001L, LocalDateTime.of(2026, 4, 7, 9, 0)),
            exam(102L, 200L, 10002L, LocalDateTime.of(2026, 4, 8, 9, 0))
        ));
        when(submissionAnswerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            answer(11L, 1L, 501L, "答案A1"),
            answer(12L, 2L, 501L, "答案A2"),
            answer(13L, 1L, 502L, "答案B1"),
            answer(14L, 3L, 503L, "答案C1")
        ));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
            shortQuestion(501L, "题目A", 10),
            shortQuestion(502L, "题目B", 15),
            shortQuestion(503L, "题目C", 20)
        ));
        when(paperQuestionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            paperQuestion(10001L, 501L, 2, 12),
            paperQuestion(10001L, 502L, 1, 15),
            paperQuestion(10002L, 503L, 1, 20)
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            List<PendingQuestionGroupView> result = gradingService.pendingQuestionGroups();

            assertEquals(3, result.size());
            assertEquals(102L, result.get(0).getExamId());
            assertEquals(503L, result.get(0).getQuestionId());
            assertEquals(101L, result.get(1).getExamId());
            assertEquals(502L, result.get(1).getQuestionId());
            assertEquals(12, result.get(2).getDefaultScore());
            assertEquals(2, result.get(2).getPendingCount());
        }
    }

    @Test
    void pendingQuestionAnswersShouldOnlyReturnTargetExamAndQuestion() {
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examMapper.selectById(101L)).thenReturn(exam(101L, 200L, 10001L, LocalDateTime.of(2026, 4, 7, 9, 0)));
        when(questionMapper.selectById(501L)).thenReturn(shortQuestion(501L, "题目A", 10));
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            submission(1L, 101L, 1001L, LocalDateTime.of(2026, 4, 7, 10, 0)),
            submission(2L, 101L, 1002L, LocalDateTime.of(2026, 4, 7, 10, 5))
        ));
        when(submissionAnswerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            answer(11L, 1L, 501L, "答案A1"),
            answer(12L, 2L, 501L, "答案A2")
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
            user(1001L, "Alice"),
            user(1002L, "Bob")
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            List<PendingQuestionAnswerView> result = gradingService.pendingQuestionAnswers(501L, 101L);

            assertEquals(2, result.size());
            assertEquals("Alice", result.get(0).getStudentName());
            assertEquals("Bob", result.get(1).getStudentName());
            assertTrue(result.get(0).getSubmittedAt().isBefore(result.get(1).getSubmittedAt()));
        }
    }

    @Test
    void scoreQuestionAnswersShouldUpdateEachSubmissionTotals() {
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examMapper.selectById(101L)).thenReturn(exam(101L, 200L, 10001L, LocalDateTime.of(2026, 4, 7, 9, 0)));
        when(questionMapper.selectById(501L)).thenReturn(shortQuestion(501L, "题目A", 5));
        when(paperQuestionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            paperQuestion(10001L, 501L, 1, 8)
        ));
        when(submissionAnswerMapper.selectBatchIds(List.of(11L, 12L))).thenReturn(List.of(
            answer(11L, 1L, 501L, "答案A1"),
            answer(12L, 2L, 501L, "答案A2")
        ));
        when(submissionMapper.selectBatchIds(any())).thenReturn(List.of(
            submission(1L, 101L, 1001L, LocalDateTime.of(2026, 4, 7, 10, 0)),
            submission(2L, 101L, 1002L, LocalDateTime.of(2026, 4, 7, 10, 5))
        ));
        when(submissionAnswerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            scoredAnswer(11L, 1L, 501L, 8, 12),
            scoredAnswer(21L, 1L, 601L, null, 20),
            scoredAnswer(12L, 2L, 501L, 8, 15)
        ));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
            shortQuestion(501L, "题目A", 10),
            choiceQuestion(601L)
        ));

        QuestionBatchScoreRequest request = new QuestionBatchScoreRequest();
        request.setExamId(101L);
        request.setSubmissionAnswerIds(List.of(11L, 12L));
        request.setScore(8);
        request.setComment("同类答案统一评分");

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            gradingService.scoreQuestionAnswers(501L, request);

            verify(submissionAnswerMapper, times(2)).updateById(any(SubmissionAnswer.class));
            verify(subjectiveGradeMapper, times(2)).insert(any(com.ekusys.exam.repository.entity.SubjectiveGrade.class));
            verify(submissionMapper, times(2)).updateById(any(Submission.class));
        }
    }

    @Test
    void scoreQuestionAnswersShouldRejectScoreBeyondPaperQuestionScore() {
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examMapper.selectById(101L)).thenReturn(exam(101L, 200L, 10001L, LocalDateTime.of(2026, 4, 7, 9, 0)));
        when(questionMapper.selectById(501L)).thenReturn(shortQuestion(501L, "题目A", 5));
        when(paperQuestionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            paperQuestion(10001L, 501L, 1, 10)
        ));

        QuestionBatchScoreRequest request = new QuestionBatchScoreRequest();
        request.setExamId(101L);
        request.setSubmissionAnswerIds(List.of(11L));
        request.setScore(11);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            assertThrows(BusinessException.class, () -> gradingService.scoreQuestionAnswers(501L, request));
        }
    }

    @Test
    void scoreSubjectiveShouldRejectUnauthorizedExam() {
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(submissionMapper.selectById(1L)).thenReturn(submission(1L, 102L, 1002L, LocalDateTime.of(2026, 4, 7, 10, 0)));
        when(examMapper.selectById(102L)).thenReturn(exam(102L, 999L, 10002L, LocalDateTime.of(2026, 4, 7, 9, 0)));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        SubjectiveScoreRequest request = new SubjectiveScoreRequest();
        SubjectiveScoreItem item = new SubjectiveScoreItem();
        item.setSubmissionAnswerId(12L);
        item.setScore(8);
        request.setScores(List.of(item));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            assertThrows(BusinessException.class, () -> gradingService.scoreSubjective(1L, request));
        }
    }

    private Submission submission(Long id, Long examId, Long studentId) {
        return submission(id, examId, studentId, LocalDateTime.of(2026, 4, 7, 10, 0));
    }

    private Submission submission(Long id, Long examId, Long studentId, LocalDateTime submittedAt) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setExamId(examId);
        submission.setStudentId(studentId);
        submission.setStatus("SUBMITTED");
        submission.setSubmittedAt(submittedAt);
        return submission;
    }

    private Exam exam(Long id, Long publisherId, Long paperId, LocalDateTime startTime) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setName("exam-" + id);
        exam.setPublisherId(publisherId);
        exam.setPaperId(paperId);
        exam.setStartTime(startTime);
        exam.setPassScore(60);
        return exam;
    }

    private User user(Long id, String realName) {
        User user = new User();
        user.setId(id);
        user.setRealName(realName);
        return user;
    }

    private SubmissionAnswer answer(Long id, Long submissionId, Long questionId, String answerText) {
        SubmissionAnswer answer = new SubmissionAnswer();
        answer.setId(id);
        answer.setSubmissionId(submissionId);
        answer.setQuestionId(questionId);
        answer.setAnswerText(answerText);
        return answer;
    }

    private SubmissionAnswer scoredAnswer(Long id, Long submissionId, Long questionId, Integer subjectiveScore, Integer objectiveScore) {
        SubmissionAnswer answer = new SubmissionAnswer();
        answer.setId(id);
        answer.setSubmissionId(submissionId);
        answer.setQuestionId(questionId);
        answer.setSubjectiveScore(subjectiveScore);
        answer.setObjectiveScore(objectiveScore);
        return answer;
    }

    private PaperQuestion paperQuestion(Long paperId, Long questionId, Integer sortOrder, Integer score) {
        PaperQuestion paperQuestion = new PaperQuestion();
        paperQuestion.setPaperId(paperId);
        paperQuestion.setQuestionId(questionId);
        paperQuestion.setSortOrder(sortOrder);
        paperQuestion.setScore(score);
        return paperQuestion;
    }

    private Question shortQuestion(Long id, String content, Integer defaultScore) {
        Question question = new Question();
        question.setId(id);
        question.setType("SHORT");
        question.setContent(content);
        question.setAnswer("参考答案-" + id);
        question.setAnalysis("解析-" + id);
        question.setDefaultScore(defaultScore);
        return question;
    }

    private Question choiceQuestion(Long id) {
        Question question = new Question();
        question.setId(id);
        question.setType("SINGLE");
        return question;
    }
}
