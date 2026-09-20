package com.ekusys.exam.exam.service;

import com.ekusys.exam.common.enums.ExamStatus;
import com.ekusys.exam.common.enums.SessionStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.StartExamResponse;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamSession;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamStartService {

    private static final Logger log = LoggerFactory.getLogger(ExamStartService.class);

    private final ExamAccessService examAccessService;
    private final ExamStatusService examStatusService;
    private final ExamSessionService examSessionService;
    private final ExamQuestionAssembler examQuestionAssembler;
    private final ExamSnapshotService examSnapshotService;
    private final ExamProctoringPolicyService proctoringPolicyService;

    public ExamStartService(ExamAccessService examAccessService,
                            ExamStatusService examStatusService,
                            ExamSessionService examSessionService,
                            ExamQuestionAssembler examQuestionAssembler,
                            ExamSnapshotService examSnapshotService,
                            ExamProctoringPolicyService proctoringPolicyService) {
        this.examAccessService = examAccessService;
        this.examStatusService = examStatusService;
        this.examSessionService = examSessionService;
        this.examQuestionAssembler = examQuestionAssembler;
        this.examSnapshotService = examSnapshotService;
        this.proctoringPolicyService = proctoringPolicyService;
    }

    @Transactional
    public StartExamResponse startExam(Long examId) {
        Long studentId = examAccessService.getCurrentUserId();
        Exam exam = examAccessService.ensureExam(examId);
        examAccessService.checkStudentAccess(examId, studentId);

        LocalDateTime now = LocalDateTime.now();
        examStatusService.refreshExamStatusByTime(exam, now);
        if (now.isBefore(exam.getStartTime())) {
            throw new BusinessException("考试尚未开始");
        }
        if (now.isAfter(exam.getEndTime())) {
            throw new BusinessException("考试已结束");
        }
        if (ExamStatus.DRAFT.name().equals(exam.getStatus())) {
            throw new BusinessException("考试未发布");
        }
        if (ExamStatus.TERMINATED.name().equals(exam.getStatus())) {
            throw new BusinessException("考试已终止");
        }
        if (ExamStatus.FINISHED.name().equals(exam.getStatus())) {
            throw new BusinessException("考试已结束");
        }

        LocalDateTime deadlineTime = resolveDeadlineTime(exam, now);
        ExamSession existingSession = examSessionService.findLatestSession(examId, studentId);
        var session = examSessionService.startAnsweringSession(examId, studentId, now, deadlineTime);
        examSessionService.ensureInProgressSubmission(examId, studentId);
        log.info("Exam started: examId={}, studentId={}", examId, studentId);

        return StartExamResponse.builder()
            .examId(exam.getId())
            .examName(exam.getName())
            .resumed(isResumed(existingSession))
            .durationMinutes(exam.getDurationMinutes())
            .startTime(exam.getStartTime())
            .endTime(exam.getEndTime())
            .deadlineTime(session.getDeadlineTime())
            .draftUpdatedAt(examSnapshotService.resolveDraftUpdatedAt(examId, studentId))
            .proctoringPolicy(proctoringPolicyService.resolve(exam.getProctoringLevel(), exam.getProctoringConfigJson()))
            .questions(examQuestionAssembler.assembleQuestions(exam.getPaperId(), examId, studentId))
            .build();
    }

    private boolean isResumed(ExamSession session) {
        if (session == null || session.getStatus() == null) {
            return false;
        }
        return !SessionStatus.SUBMITTED.name().equals(session.getStatus())
            && !SessionStatus.TIMEOUT.name().equals(session.getStatus());
    }

    private LocalDateTime resolveDeadlineTime(Exam exam, LocalDateTime now) {
        if (exam == null || now == null) {
            return null;
        }
        LocalDateTime candidate = now.plusMinutes(Math.max(1, exam.getDurationMinutes() == null ? 1 : exam.getDurationMinutes()));
        if (exam.getEndTime() == null || candidate.isBefore(exam.getEndTime())) {
            return candidate;
        }
        return exam.getEndTime();
    }
}
