package com.ekusys.exam.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.config.TimeoutSubmissionProperties;
import com.ekusys.exam.runtime.repository.TimeoutSessionMapper;
import com.ekusys.exam.runtime.repository.TimeoutSessionRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionTemplate;

class TimeoutSubmissionServiceTest {
    private TimeoutSessionMapper mapper;
    private RuntimeOutboxService outbox;
    private TransactionTemplate transactions;
    private ExamSnapshotService snapshotService;
    private SnapshotPersistenceService snapshotPersistence;
    private TimeoutSubmissionProperties properties;
    private TimeoutSubmissionCoordinator coordinator;
    private TimeoutSubmissionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(TimeoutSessionMapper.class);
        outbox = mock(RuntimeOutboxService.class);
        transactions = mock(TransactionTemplate.class);
        snapshotService = mock(ExamSnapshotService.class);
        snapshotPersistence = mock(SnapshotPersistenceService.class);
        properties = new TimeoutSubmissionProperties();
        coordinator = mock(TimeoutSubmissionCoordinator.class);
        service = new TimeoutSubmissionService(
            mapper, outbox, transactions, snapshotService, snapshotPersistence,
            properties, coordinator
        );
    }

    @Test
    void processesAtMostOneBatchWhenEveryItemFails() {
        List<TimeoutSessionRow> rows = LongStream.range(1, 201)
            .mapToObj(id -> new TimeoutSessionRow(id, 10L, id, LocalDateTime.now()))
            .toList();
        when(mapper.findClaimable(0, 1, 200)).thenReturn(rows);
        when(transactions.execute(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertEquals(0, service.processShard(0, 1));

        verify(mapper, times(1)).findClaimable(0, 1, 200);
        verify(transactions, times(200)).execute(any());
    }

    @Test
    void duplicateClaimDoesNotCreateAnotherSubmission() {
        TimeoutSessionRow row = new TimeoutSessionRow(1L, 2L, 3L, LocalDateTime.now());
        when(mapper.claim(1L)).thenReturn(0);

        assertFalse(service.submitOne(row));

        verify(mapper, never()).createSubmission(any(), any(), any());
        verify(outbox, never()).submissionAccepted(any());
    }

    @Test
    void claimedSessionCreatesOutboxBeforeFinalState() {
        TimeoutSessionRow row = new TimeoutSessionRow(1L, 2L, 3L, LocalDateTime.now());
        when(mapper.claim(1L)).thenReturn(1);
        when(mapper.findSubmissionId(2L, 3L)).thenReturn(99L);
        when(snapshotService.loadLatestDraft(2L, 3L))
            .thenReturn(new SnapshotDraft(Map.of(10L, "A"), 12L, LocalDateTime.now()));

        assertTrue(service.submitOne(row));

        InOrder order = inOrder(mapper, snapshotService, snapshotPersistence, outbox);
        order.verify(mapper).claim(1L);
        order.verify(snapshotService).loadLatestDraft(2L, 3L);
        order.verify(mapper).createSubmission(any(), org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.eq(3L));
        order.verify(mapper).findSubmissionId(2L, 3L);
        order.verify(snapshotPersistence).replaceFinalAnswers(99L, Map.of(10L, "A"), "TIMEOUT_SUBMIT");
        order.verify(outbox).submissionAccepted(99L);
        order.verify(mapper).markSubmitted(1L);
        order.verify(snapshotService).clearAfterCommit(2L, 3L);
    }

    @Test
    void v2PassesShardParametersToDurableTaskCoordinator() {
        properties.setEnabled(true);
        when(coordinator.processDue(1, 3)).thenReturn(321);

        assertEquals(321, service.processShard(1, 3));

        verify(coordinator).processDue(1, 3);
        verify(mapper, never()).findClaimable(anyInt(), anyInt(), anyInt());
    }

    @Test
    void v1StillClaimsWithFixedShards() {
        when(mapper.findClaimable(1, 3, 200)).thenReturn(List.of());

        assertEquals(0, service.processShard(1, 3));

        verify(mapper).findClaimable(1, 3, 200);
        verify(coordinator, never()).processDue(anyInt(), anyInt());
    }

    @Test
    void invalidShardParametersFailBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.processShard(-1, 3));
        assertThrows(IllegalArgumentException.class, () -> service.processShard(3, 3));
        assertThrows(IllegalArgumentException.class, () -> service.processShard(0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.processShard(5, -2));
        verifyNoInteractions(mapper, coordinator);

        properties.setEnabled(true);
        assertThrows(IllegalArgumentException.class, () -> service.processShard(2, 2));
        verifyNoInteractions(mapper, coordinator);
    }
}
