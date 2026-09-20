package com.ekusys.exam.exam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.enums.SubmissionStatus;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExamSubmissionProcessingServiceTest {

    @Mock
    private ExamAccessService examAccessService;

    @Mock
    private SubmissionMapper submissionMapper;

    @Mock
    private SubmissionAnswerMapper submissionAnswerMapper;

    @Mock
    private PaperQuestionMapper paperQuestionMapper;

    @Mock
    private QuestionMapper questionMapper;

    @InjectMocks
    private ExamSubmissionProcessingService examSubmissionProcessingService;

    @Test
    void processAcceptedSubmissionShouldGradeObjectiveOnlyExam() {
        Submission submission = submission(88L, SubmissionStatus.PROCESSING.name());
        Exam exam = exam(1L, 55L, 60);
        PaperQuestion q1Link = paperQuestion(1001L, 30, 1);
        PaperQuestion q2Link = paperQuestion(1002L, 30, 2);
        SubmissionAnswer a1 = submissionAnswer(5001L, 88L, 1001L, "A");
        SubmissionAnswer a2 = submissionAnswer(5002L, 88L, 1002L, "B");
        Question q1 = question(1001L, "SINGLE", "A");
        Question q2 = question(1002L, "JUDGE", "false");

        when(submissionMapper.selectById(88L)).thenReturn(submission);
        when(examAccessService.ensureExam(1L)).thenReturn(exam);
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of(q1Link, q2Link));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1, q2));
        when(submissionAnswerMapper.selectList(any())).thenReturn(List.of(a1, a2));

        SubmitResultView result = examSubmissionProcessingService.processAcceptedSubmission(88L);

        assertEquals("GRADED", result.getStatus());
        assertEquals(30, result.getObjectiveScore());
        assertEquals(0, result.getSubjectiveScore());
        assertEquals(30, result.getTotalScore());
        assertFalse(result.getPassFlag());
        verify(submissionAnswerMapper).updateById(a1);
        verify(submissionAnswerMapper).updateById(a2);
        verify(submissionMapper).updateById(submission);
    }

    @Test
    void processAcceptedSubmissionShouldLeaveSubjectiveExamPendingTeacherGrading() {
        Submission submission = submission(89L, SubmissionStatus.PROCESSING.name());
        Exam exam = exam(1L, 55L, 60);
        PaperQuestion q1Link = paperQuestion(1001L, 30, 1);
        PaperQuestion q2Link = paperQuestion(1002L, 40, 2);
        SubmissionAnswer a1 = submissionAnswer(5001L, 89L, 1001L, "A");
        SubmissionAnswer a2 = submissionAnswer(5002L, 89L, 1002L, "主观答案");
        Question q1 = question(1001L, "SINGLE", "A");
        Question q2 = question(1002L, "SHORT", "参考答案");

        when(submissionMapper.selectById(89L)).thenReturn(submission);
        when(examAccessService.ensureExam(1L)).thenReturn(exam);
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of(q1Link, q2Link));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1, q2));
        when(submissionAnswerMapper.selectList(any())).thenReturn(List.of(a1, a2));

        SubmitResultView result = examSubmissionProcessingService.processAcceptedSubmission(89L);

        assertEquals("SUBMITTED", result.getStatus());
        assertEquals(30, result.getObjectiveScore());
        assertNull(result.getSubjectiveScore());
        assertNull(result.getTotalScore());
        assertNull(result.getPassFlag());
        verify(submissionAnswerMapper).updateById(a1);
        verify(submissionAnswerMapper).updateById(a2);
        verify(submissionMapper).updateById(submission);
    }

    @Test
    void processAcceptedSubmissionShouldIgnoreDuplicateMessageWhenAlreadyProcessed() {
        Submission submission = submission(90L, SubmissionStatus.GRADED.name());
        submission.setObjectiveScore(60);
        submission.setSubjectiveScore(0);
        submission.setTotalScore(60);
        submission.setPassFlag(true);

        when(submissionMapper.selectById(90L)).thenReturn(submission);

        SubmitResultView result = examSubmissionProcessingService.processAcceptedSubmission(90L);

        assertEquals("GRADED", result.getStatus());
        assertEquals(60, result.getTotalScore());
        assertTrue(result.getPassFlag());
        verify(examAccessService, never()).ensureExam(any());
        verify(submissionAnswerMapper, never()).selectList(any());
        verify(submissionMapper, never()).updateById(org.mockito.ArgumentMatchers.<Submission>any());
    }

    private Submission submission(Long id, String status) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setExamId(1L);
        submission.setStudentId(11L);
        submission.setStatus(status);
        return submission;
    }

    private Exam exam(Long id, Long paperId, Integer passScore) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setPaperId(paperId);
        exam.setPassScore(passScore);
        return exam;
    }

    private PaperQuestion paperQuestion(Long questionId, Integer score, Integer sortOrder) {
        PaperQuestion paperQuestion = new PaperQuestion();
        paperQuestion.setPaperId(55L);
        paperQuestion.setQuestionId(questionId);
        paperQuestion.setScore(score);
        paperQuestion.setSortOrder(sortOrder);
        return paperQuestion;
    }

    private Question question(Long id, String type, String answer) {
        Question question = new Question();
        question.setId(id);
        question.setType(type);
        question.setAnswer(answer);
        return question;
    }

    private SubmissionAnswer submissionAnswer(Long id, Long submissionId, Long questionId, String answerText) {
        SubmissionAnswer answer = new SubmissionAnswer();
        answer.setId(id);
        answer.setSubmissionId(submissionId);
        answer.setQuestionId(questionId);
        answer.setAnswerText(answerText);
        return answer;
    }
}
