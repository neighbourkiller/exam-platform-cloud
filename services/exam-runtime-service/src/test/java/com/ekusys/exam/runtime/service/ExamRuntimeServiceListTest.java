package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.management.api.RuntimeStudentExamSummary;
import com.ekusys.exam.runtime.client.ManagementRuntimeClient;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;

class ExamRuntimeServiceListTest {
    @Test
    void listUsesSummaryContractAndBatchesSubmissionStatus() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ManagementRuntimeClient management = mock(ManagementRuntimeClient.class);
        when(jdbc.queryForList(
            "select exam_id from submission where student_id=? and status<>'IN_PROGRESS'",
            Long.class,
            7L
        )).thenReturn(List.of(11L));
        when(management.summaries(7L)).thenReturn(ApiResponse.ok(List.of(
            new RuntimeStudentExamSummary(
                11L, "Java", LocalDateTime.of(2026, 7, 24, 10, 0),
                LocalDateTime.of(2026, 7, 24, 12, 0), 120,
                "PUBLISHED", "STANDARD", null
            ),
            new RuntimeStudentExamSummary(
                12L, "数据库", LocalDateTime.of(2026, 7, 25, 10, 0),
                LocalDateTime.of(2026, 7, 25, 12, 0), 120,
                "PUBLISHED", "STANDARD", null
            )
        )));
        ExamRuntimeService service = new ExamRuntimeService(
            jdbc, management, new ObjectMapper(), mock(RuntimeOutboxService.class),
            mock(TimeoutSubmissionService.class), mock(ExamSnapshotService.class),
            mock(ExamClientLeaseService.class), mock(ExamAnswerInputValidator.class),
            mock(com.ekusys.exam.runtime.repository.TimeoutTaskRepository.class),
            mock(ManualSubmissionService.class), mock(SubmissionFinalPayloadService.class),
            mock(SubmissionStatusProjectionService.class),
            mock(org.springframework.transaction.support.TransactionTemplate.class)
        );

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(7L);

            var result = service.listStudent();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getSubmitted()).isTrue();
            assertThat(result.get(1).getSubmitted()).isFalse();
        }
        verify(management).summaries(7L);
        verify(jdbc).queryForList(
            "select exam_id from submission where student_id=? and status<>'IN_PROGRESS'",
            Long.class,
            7L
        );
    }
}
