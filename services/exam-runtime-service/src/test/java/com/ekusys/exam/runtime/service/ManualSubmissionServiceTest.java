package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.SessionState;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskRow;
import com.ekusys.exam.runtime.service.SubmissionFinalPayloadService.EncodedFinalAnswers;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ManualSubmissionServiceTest {
    private TimeoutTaskRepository tasks;
    private SubmissionFinalPayloadService finalPayloads;
    private RuntimeOutboxService outbox;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ManualSubmissionService service;
    private final LocalDateTime deadline = LocalDateTime.of(2026, 8, 8, 20, 0);
    private final EncodedFinalAnswers encoded = new EncodedFinalAnswers(12L, new byte[] {1}, "abc");

    @BeforeEach
    void setUp() {
        tasks = mock(TimeoutTaskRepository.class);
        finalPayloads = mock(SubmissionFinalPayloadService.class);
        outbox = mock(RuntimeOutboxService.class);
        jdbc = mock(JdbcTemplate.class);
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class))
            .thenReturn(deadline.minusSeconds(1));
        when(tasks.markDoneLocked(any())).thenReturn(1);
        SubmissionStatusProjectionService projectionService = mock(SubmissionStatusProjectionService.class);
        service = new ManualSubmissionService(tasks, finalPayloads, outbox, projectionService, jdbc, transactions);
    }

    @Test
    void alreadySubmittedRequestConvergesWithoutAnotherPayloadOrEvent() {
        when(tasks.lockBySession(1L)).thenReturn(task("PENDING"));
        when(tasks.lockSession(1L)).thenReturn(session("SUBMITTED"));

        var result = service.submit(1L, 2L, 3L, 4L, request(), encoded);

        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(result.completedNow()).isFalse();
        verify(tasks).markDoneLocked(1L);
        verify(finalPayloads, never()).store(any(), anyString(), any());
        verify(outbox, never()).submissionAccepted(any());
    }

    @Test
    void workerOwnedTaskReturnsSubmittingWithoutOverwritingAnswers() {
        when(tasks.lockBySession(1L)).thenReturn(task("PROCESSING"));
        when(tasks.lockSession(1L)).thenReturn(session("AUTO_SUBMITTING"));

        var result = service.submit(1L, 2L, 3L, 4L, request(), encoded);

        assertThat(result.status()).isEqualTo("SUBMITTING");
        verify(finalPayloads, never()).store(any(), anyString(), any());
        verify(outbox, never()).submissionAccepted(any());
    }

    @Test
    void winnerWritesPayloadEventAndFinalStatesInOneOrder() {
        SubmitExamRequest request = request();
        when(tasks.lockBySession(1L)).thenReturn(task("PENDING"));
        when(tasks.lockSession(1L)).thenReturn(session("ANSWERING"));
        when(tasks.claimManualSession(1L, request.getClientId(), request.getLeaseToken())).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(tasks.markSessionSubmitted(1L)).thenReturn(1);

        var result = service.submit(1L, 2L, 3L, 4L, request, encoded);

        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(result.completedNow()).isTrue();
        InOrder order = inOrder(tasks, finalPayloads, outbox, jdbc);
        order.verify(tasks).lockBySession(1L);
        order.verify(tasks).lockSession(1L);
        order.verify(tasks).claimManualSession(1L, "client-1", "lease-1");
        order.verify(finalPayloads).store(4L, "MANUAL", encoded);
        order.verify(jdbc).update(anyString(), any(Object[].class));
        order.verify(outbox).submissionAccepted(4L);
        order.verify(tasks).markSessionSubmitted(1L);
        order.verify(tasks).markDoneLocked(1L);
    }

    @Test
    void deadlockRetriesTheWholeSubmissionTransaction() {
        AtomicInteger attempts = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new DeadlockLoserDataAccessException("deadlock", null);
            }
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        when(tasks.lockBySession(1L)).thenReturn(task("SUBMITTED"));
        when(tasks.lockSession(1L)).thenReturn(session("SUBMITTED"));

        var result = service.submit(1L, 2L, 3L, 4L, request(), encoded);

        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(attempts).hasValue(2);
    }

    private TaskRow task(String status) {
        return new TaskRow(1L, 1L, 2L, 3L, deadline, status, null, null, 0, null);
    }

    private SessionState session(String status) {
        return new SessionState(1L, 2L, 3L, status, deadline);
    }

    private SubmitExamRequest request() {
        SubmitExamRequest request = new SubmitExamRequest();
        request.setClientId("client-1");
        request.setLeaseToken("lease-1");
        return request;
    }
}
