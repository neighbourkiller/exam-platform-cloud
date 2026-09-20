package com.ekusys.exam.exam;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.exam.service.ExamAutoSubmitService;
import com.ekusys.exam.exam.service.ExamSessionService;
import com.ekusys.exam.exam.service.ExamSubmissionService;
import com.ekusys.exam.repository.entity.ExamSession;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExamAutoSubmitServiceTest {

    @Mock
    private ExamSessionService examSessionService;

    @Mock
    private ExamSubmissionService examSubmissionService;

    @InjectMocks
    private ExamAutoSubmitService examAutoSubmitService;

    @Test
    void autoSubmitExpiredSessionsShouldSubmitEachExpiredSession() {
        ExamSession first = session(1L, 101L, 1001L);
        ExamSession second = session(2L, 102L, 1002L);
        when(examSessionService.listExpiredAnsweringSessions(any(LocalDateTime.class))).thenReturn(List.of(first, second));

        examAutoSubmitService.autoSubmitExpiredSessions();

        verify(examSubmissionService).submitExpiredSession(eq(101L), eq(1001L), any(LocalDateTime.class));
        verify(examSubmissionService).submitExpiredSession(eq(102L), eq(1002L), any(LocalDateTime.class));
    }

    private ExamSession session(Long id, Long examId, Long studentId) {
        ExamSession session = new ExamSession();
        session.setId(id);
        session.setExamId(examId);
        session.setStudentId(studentId);
        return session;
    }
}
