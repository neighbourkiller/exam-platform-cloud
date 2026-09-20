package com.ekusys.exam.exam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.enums.SessionStatus;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.exam.dto.SubmitResultView;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExamSubmissionServiceTest {

    @Mock
    private ExamAccessService examAccessService;

    @Mock
    private ExamSessionService examSessionService;

    @Mock
    private ExamSnapshotService examSnapshotService;

    @Mock
    private ExamSubmissionAcceptanceService examSubmissionAcceptanceService;

    @Mock
    private ExamSubmissionProcessingService examSubmissionProcessingService;

    @Mock
    private ExamSubmissionMessagePublisher examSubmissionMessagePublisher;

    @InjectMocks
    private ExamSubmissionService examSubmissionService;

    @Test
    void submitShouldReturnProcessingWhenMessagePublished() {
        Exam exam = exam(1L, 100);
        ExamSession session = activeSession(1L, 11L);
        SubmitExamRequest request = request();
        SubmitResultView processingResult = SubmitResultView.builder()
            .submissionId(99L)
            .status("PROCESSING")
            .build();

        when(examAccessService.getCurrentUserId()).thenReturn(11L);
        when(examAccessService.ensureExam(1L)).thenReturn(exam);
        doNothing().when(examAccessService).checkStudentAccess(1L, 11L);
        when(examSessionService.findLatestSession(1L, 11L)).thenReturn(session);
        when(examSessionService.isExpired(eq(session), any(LocalDateTime.class))).thenReturn(false);
        when(examSubmissionAcceptanceService.acceptSubmission(eq(exam), eq(session), eq(11L), any(), any(LocalDateTime.class), eq(false)))
            .thenReturn(new ExamSubmissionAcceptedContext(99L, 1L, 11L, LocalDateTime.now(), false));
        when(examSubmissionProcessingService.buildProcessingResult(99L)).thenReturn(processingResult);

        SubmitResultView result = examSubmissionService.submit(1L, request);

        assertEquals("PROCESSING", result.getStatus());
        assertEquals(99L, result.getSubmissionId());
        assertNull(result.getTotalScore());
        verify(examSubmissionMessagePublisher).publish(any());
        verify(examSubmissionProcessingService, never()).processAcceptedSubmission(any());
    }

    @Test
    void submitShouldFallbackToSyncProcessingWhenPublishFails() {
        Exam exam = exam(1L, 100);
        ExamSession session = activeSession(1L, 11L);
        SubmitExamRequest request = request();
        SubmitResultView gradedResult = SubmitResultView.builder()
            .submissionId(99L)
            .objectiveScore(60)
            .subjectiveScore(0)
            .totalScore(60)
            .passFlag(true)
            .status("GRADED")
            .build();

        when(examAccessService.getCurrentUserId()).thenReturn(11L);
        when(examAccessService.ensureExam(1L)).thenReturn(exam);
        doNothing().when(examAccessService).checkStudentAccess(1L, 11L);
        when(examSessionService.findLatestSession(1L, 11L)).thenReturn(session);
        when(examSessionService.isExpired(eq(session), any(LocalDateTime.class))).thenReturn(false);
        when(examSubmissionAcceptanceService.acceptSubmission(eq(exam), eq(session), eq(11L), any(), any(LocalDateTime.class), eq(false)))
            .thenReturn(new ExamSubmissionAcceptedContext(99L, 1L, 11L, LocalDateTime.now(), false));
        org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
            .when(examSubmissionMessagePublisher).publish(any());
        when(examSubmissionProcessingService.processAcceptedSubmission(99L)).thenReturn(gradedResult);

        SubmitResultView result = examSubmissionService.submit(1L, request);

        assertEquals("GRADED", result.getStatus());
        assertEquals(60, result.getTotalScore());
        verify(examSubmissionProcessingService).processAcceptedSubmission(99L);
        verify(examSubmissionProcessingService, never()).buildProcessingResult(any());
    }

    @Test
    void submitShouldKeepEmptyAnswerText() {
        Exam exam = exam(1L, 100);
        ExamSession session = activeSession(1L, 11L);
        SubmitExamRequest request = new SubmitExamRequest();
        request.setAnswers(List.of(answer(1001L, "")));
        when(examAccessService.getCurrentUserId()).thenReturn(11L);
        when(examAccessService.ensureExam(1L)).thenReturn(exam);
        doNothing().when(examAccessService).checkStudentAccess(1L, 11L);
        when(examSessionService.findLatestSession(1L, 11L)).thenReturn(session);
        when(examSessionService.isExpired(eq(session), any(LocalDateTime.class))).thenReturn(false);
        when(examSubmissionAcceptanceService.acceptSubmission(eq(exam), eq(session), eq(11L), any(), any(LocalDateTime.class), eq(false)))
            .thenReturn(new ExamSubmissionAcceptedContext(99L, 1L, 11L, LocalDateTime.now(), false));
        when(examSubmissionProcessingService.buildProcessingResult(99L)).thenReturn(SubmitResultView.builder()
            .submissionId(99L)
            .status("PROCESSING")
            .build());

        examSubmissionService.submit(1L, request);

        ArgumentCaptor<Map<Long, String>> answersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(examSubmissionAcceptanceService).acceptSubmission(
            eq(exam),
            eq(session),
            eq(11L),
            answersCaptor.capture(),
            any(LocalDateTime.class),
            eq(false)
        );
        assertEquals("", answersCaptor.getValue().get(1001L));
    }

    private Exam exam(Long id, Integer passScore) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setPassScore(passScore);
        return exam;
    }

    private ExamSession activeSession(Long examId, Long studentId) {
        ExamSession session = new ExamSession();
        session.setExamId(examId);
        session.setStudentId(studentId);
        session.setStatus(SessionStatus.ANSWERING.name());
        session.setDeadlineTime(LocalDateTime.now().plusMinutes(30));
        return session;
    }

    private SubmitExamRequest request() {
        SubmitExamRequest request = new SubmitExamRequest();
        request.setAnswers(List.of(answer(1001L, "A")));
        return request;
    }

    private AnswerPayload answer(Long questionId, String answerText) {
        AnswerPayload payload = new AnswerPayload();
        payload.setQuestionId(questionId);
        payload.setAnswerText(answerText);
        return payload;
    }
}
