package com.ekusys.exam.exam.service;

import com.ekusys.exam.common.enums.SessionStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.ExamSubmissionMessage;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.exam.dto.SubmitResultView;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamSession;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExamSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ExamSubmissionService.class);

    private final ExamAccessService examAccessService;
    private final ExamSessionService examSessionService;
    private final ExamSnapshotService examSnapshotService;
    private final ExamSubmissionAcceptanceService examSubmissionAcceptanceService;
    private final ExamSubmissionProcessingService examSubmissionProcessingService;
    private final ExamSubmissionMessagePublisher examSubmissionMessagePublisher;

    public ExamSubmissionService(ExamAccessService examAccessService,
                                 ExamSessionService examSessionService,
                                 ExamSnapshotService examSnapshotService,
                                 ExamSubmissionAcceptanceService examSubmissionAcceptanceService,
                                 ExamSubmissionProcessingService examSubmissionProcessingService,
                                 ExamSubmissionMessagePublisher examSubmissionMessagePublisher) {
        this.examAccessService = examAccessService;
        this.examSessionService = examSessionService;
        this.examSnapshotService = examSnapshotService;
        this.examSubmissionAcceptanceService = examSubmissionAcceptanceService;
        this.examSubmissionProcessingService = examSubmissionProcessingService;
        this.examSubmissionMessagePublisher = examSubmissionMessagePublisher;
    }

    public SubmitResultView submit(Long examId, SubmitExamRequest request) {
        Long studentId = examAccessService.getCurrentUserId();
        Exam exam = examAccessService.ensureExam(examId);
        examAccessService.checkStudentAccess(examId, studentId);

        ExamSession session = requireSubmittableSession(examId, studentId);
        Map<Long, String> answerMap = request.getAnswers().stream()
            .collect(Collectors.toMap(AnswerPayload::getQuestionId, AnswerPayload::getAnswerText, (a, b) -> b));

        ExamSubmissionAcceptedContext accepted = examSubmissionAcceptanceService.acceptSubmission(
            exam,
            session,
            studentId,
            answerMap,
            LocalDateTime.now(),
            false
        );
        return publishOrFallback(accepted);
    }

    public void submitExpiredSession(Long examId, Long studentId, LocalDateTime submittedAt) {
        Exam exam = examAccessService.ensureExam(examId);
        ExamSession session = examSessionService.findLatestSession(examId, studentId);
        if (session == null || SessionStatus.SUBMITTED.name().equals(session.getStatus())
            || SessionStatus.TIMEOUT.name().equals(session.getStatus())) {
            return;
        }
        Map<Long, String> answerMap = examSnapshotService.loadSnapshotAnswerMap(examId, studentId);
        ExamSubmissionAcceptedContext accepted = examSubmissionAcceptanceService.acceptSubmission(
            exam,
            session,
            studentId,
            answerMap,
            submittedAt == null ? LocalDateTime.now() : submittedAt,
            true
        );
        publishOrFallback(accepted);
    }

    private ExamSession requireSubmittableSession(Long examId, Long studentId) {
        ExamSession session = examSessionService.findLatestSession(examId, studentId);
        if (session == null || SessionStatus.SUBMITTED.name().equals(session.getStatus())
            || SessionStatus.TIMEOUT.name().equals(session.getStatus())) {
            throw new BusinessException("不可重复交卷");
        }
        if (examSessionService.isExpired(session, LocalDateTime.now())) {
            throw new BusinessException("考试作答时间已结束，系统将自动交卷");
        }
        return session;
    }

    private SubmitResultView publishOrFallback(ExamSubmissionAcceptedContext accepted) {
        ExamSubmissionMessage message = ExamSubmissionMessage.builder()
            .submissionId(accepted.submissionId())
            .examId(accepted.examId())
            .studentId(accepted.studentId())
            .submittedAt(accepted.submittedAt())
            .timeoutSubmit(accepted.timeoutSubmit())
            .build();
        try {
            examSubmissionMessagePublisher.publish(message);
            log.info("Exam submission accepted and queued: submissionId={}, examId={}, studentId={}, timeoutSubmit={}",
                accepted.submissionId(), accepted.examId(), accepted.studentId(), accepted.timeoutSubmit());
            return examSubmissionProcessingService.buildProcessingResult(accepted.submissionId());
        } catch (Exception ex) {
            log.warn("Failed to publish exam submission message, fallback to sync processing: submissionId={}, examId={}, studentId={}",
                accepted.submissionId(), accepted.examId(), accepted.studentId(), ex);
            return examSubmissionProcessingService.processAcceptedSubmission(accepted.submissionId());
        }
    }
}
