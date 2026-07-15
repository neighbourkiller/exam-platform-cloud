package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.ExamClientLeaseView;
import com.ekusys.exam.exam.dto.SnapshotAckView;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.runtime.client.ManagementRuntimeClient;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ExamRuntimeServiceSubmissionTest {
    private static final long EXAM_ID = 11L;
    private static final long USER_ID = 7L;
    private static final long SESSION_ID = 22L;
    private static final long SUBMISSION_ID = 33L;

    @Test
    void normalSnapshotValidatesBeforeRenewAndSave() throws Exception {
        Fixture fixture = fixture(LocalDateTime.of(2026, 7, 12, 10, 5));
        SnapshotRequest request = new SnapshotRequest();
        request.setAnswers(validRequest().getAnswers());
        request.setClientId("client-1");
        request.setLeaseToken("lease-1");
        request.setClientTimestamp(100L);
        request.setSnapshotVersion(100L);
        ExamClientLeaseView lease = ExamClientLeaseView.builder()
            .leaseToken("stable-lease")
            .heartbeatIntervalSeconds(30)
            .leaseTimeoutSeconds(90)
            .build();
        SnapshotAckView saved = SnapshotAckView.builder().snapshotVersion(100L).build();
        when(fixture.leaseService.renew(
            eq(EXAM_ID), eq(USER_ID), eq(request.getClientId()), eq(request.getLeaseToken()),
            any(LocalDateTime.class), eq(true)
        )).thenReturn(new ExamClientLeaseContext(
            SESSION_ID, LocalDateTime.of(2026, 7, 12, 10, 5), fixture.now, lease
        ));
        when(fixture.snapshotService.save(
            eq(EXAM_ID), eq(USER_ID), eq(SESSION_ID),
            eq(LocalDateTime.of(2026, 7, 12, 10, 5)), any(LocalDateTime.class), eq(request)
        )).thenReturn(saved);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            SnapshotAckView result = fixture.service.snapshot(EXAM_ID, request);

            assertThat(result.getLeaseToken()).isEqualTo("stable-lease");
        }

        verify(fixture.validator).validateAnswers(request.getAnswers());
        verify(fixture.validator).validateSnapshotVersion(eq(request), any(LocalDateTime.class));
        verify(fixture.leaseService).renew(
            eq(EXAM_ID), eq(USER_ID), eq(request.getClientId()), eq(request.getLeaseToken()),
            any(LocalDateTime.class), eq(true)
        );
        verify(fixture.snapshotService).save(
            eq(EXAM_ID), eq(USER_ID), eq(SESSION_ID),
            eq(LocalDateTime.of(2026, 7, 12, 10, 5)), any(LocalDateTime.class), eq(request)
        );
    }

    @Test
    void futureSnapshotVersionDoesNotRenewLeaseOrWriteSnapshot() throws Exception {
        Fixture fixture = fixture(LocalDateTime.of(2026, 7, 12, 10, 5));
        SnapshotRequest request = new SnapshotRequest();
        request.setAnswers(validRequest().getAnswers());
        request.setClientId("client-1");
        request.setLeaseToken("lease-1");
        request.setSnapshotVersion(Long.MAX_VALUE);
        doThrow(new BusinessException(ExamAnswerInputValidator.INVALID_VERSION_CODE, "future"))
            .when(fixture.validator).validateSnapshotVersion(eq(request), any(LocalDateTime.class));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            assertThatThrownBy(() -> fixture.service.snapshot(EXAM_ID, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.getCode()).isEqualTo(ExamAnswerInputValidator.INVALID_VERSION_CODE));
        }

        verifyNoInteractions(fixture.leaseService, fixture.snapshotService);
    }

    @Test
    void normalSubmissionValidatesAndPersistsAnswers() throws Exception {
        Fixture fixture = fixture(LocalDateTime.of(2026, 7, 12, 10, 5));
        SubmitExamRequest request = validRequest();

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            var result = fixture.service.submit(EXAM_ID, request);

            assertThat(result.getSubmissionId()).isEqualTo(SUBMISSION_ID);
            assertThat(result.getStatus()).isEqualTo("PROCESSING");
        }

        verify(fixture.validator).validateSubmitRequest(request);
        verify(fixture.leaseService).requireCurrent(
            SESSION_ID, request.getClientId(), request.getLeaseToken(), fixture.now
        );
        verify(fixture.outbox).submissionAccepted(SUBMISSION_ID);
        verify(fixture.snapshotService).clearAfterCommit(EXAM_ID, USER_ID);
        verify(fixture.leaseService).clearAfterCommit(EXAM_ID, USER_ID, request.getLeaseToken());
    }

    @Test
    void expiredSubmissionIgnoresInvalidRequestAndUsesSavedDraft() throws Exception {
        Fixture fixture = fixture(LocalDateTime.of(2026, 7, 12, 9, 59));
        SubmitExamRequest invalidRequest = new SubmitExamRequest();

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            var result = fixture.service.submit(EXAM_ID, invalidRequest);

            assertThat(result.getSubmissionId()).isEqualTo(SUBMISSION_ID);
            assertThat(result.getStatus()).isEqualTo("PROCESSING");
        }

        verify(fixture.timeoutService).submitExpired(SESSION_ID, EXAM_ID, USER_ID);
        verifyNoInteractions(fixture.validator, fixture.leaseService, fixture.outbox, fixture.snapshotService);
    }

    private Fixture fixture(LocalDateTime deadline) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RuntimeOutboxService outbox = mock(RuntimeOutboxService.class);
        TimeoutSubmissionService timeoutService = mock(TimeoutSubmissionService.class);
        ExamSnapshotService snapshotService = mock(ExamSnapshotService.class);
        ExamClientLeaseService leaseService = mock(ExamClientLeaseService.class);
        ExamAnswerInputValidator validator = mock(ExamAnswerInputValidator.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 10, 0);

        doAnswer(invocation -> {
            RowMapper<?> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("id")).thenReturn(SESSION_ID);
            when(resultSet.getObject("start_time", LocalDateTime.class)).thenReturn(now.minusMinutes(30));
            when(resultSet.getObject("deadline_time", LocalDateTime.class)).thenReturn(deadline);
            when(resultSet.getString("status")).thenReturn("ANSWERING");
            return List.of(rowMapper.mapRow(resultSet, 0));
        }).when(jdbc).query(
            contains("from exam_session"), any(RowMapper.class), eq(EXAM_ID), eq(USER_ID)
        );
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class)).thenReturn(now);
        when(jdbc.queryForObject(
            contains("select id from submission"), eq(Long.class), eq(EXAM_ID), eq(USER_ID)
        )).thenReturn(SUBMISSION_ID);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ExamRuntimeService service = new ExamRuntimeService(
            jdbc,
            mock(ManagementRuntimeClient.class),
            new ObjectMapper(),
            outbox,
            timeoutService,
            snapshotService,
            leaseService,
            validator
        );
        return new Fixture(
            service, outbox, timeoutService, snapshotService, leaseService, validator, now
        );
    }

    private SubmitExamRequest validRequest() {
        AnswerPayload answer = new AnswerPayload();
        answer.setQuestionId(1L);
        answer.setAnswerText("A");
        SubmitExamRequest request = new SubmitExamRequest();
        request.setAnswers(List.of(answer));
        request.setClientId("client-1");
        request.setLeaseToken("lease-1");
        return request;
    }

    private record Fixture(
        ExamRuntimeService service,
        RuntimeOutboxService outbox,
        TimeoutSubmissionService timeoutService,
        ExamSnapshotService snapshotService,
        ExamClientLeaseService leaseService,
        ExamAnswerInputValidator validator,
        LocalDateTime now
    ) {
    }
}
