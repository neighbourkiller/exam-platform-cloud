package com.ekusys.exam.exam.service;

import com.ekusys.exam.repository.entity.ExamSession;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExamAutoSubmitService {

    private static final Logger log = LoggerFactory.getLogger(ExamAutoSubmitService.class);

    private final ExamSessionService examSessionService;
    private final ExamSubmissionService examSubmissionService;

    public ExamAutoSubmitService(ExamSessionService examSessionService,
                                 ExamSubmissionService examSubmissionService) {
        this.examSessionService = examSessionService;
        this.examSubmissionService = examSubmissionService;
    }

    public void autoSubmitExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        List<ExamSession> expiredSessions = examSessionService.listExpiredAnsweringSessions(now);
        if (expiredSessions.isEmpty()) {
            return;
        }
        for (ExamSession session : expiredSessions) {
            try {
                examSubmissionService.submitExpiredSession(session.getExamId(), session.getStudentId(), now);
            } catch (Exception ex) {
                log.error("Failed to auto submit expired session: sessionId={}, examId={}, studentId={}",
                    session.getId(), session.getExamId(), session.getStudentId(), ex);
            }
        }
    }
}
