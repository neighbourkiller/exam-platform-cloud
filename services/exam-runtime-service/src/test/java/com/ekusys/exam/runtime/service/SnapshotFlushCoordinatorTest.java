package com.ekusys.exam.runtime.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.runtime.config.SnapshotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

class SnapshotFlushCoordinatorTest {
    private SnapshotFlushQueue queue;
    private SnapshotPersistenceService persistence;
    private SnapshotFlushCoordinator coordinator;
    private SnapshotFlushClaim claim;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        queue = mock(SnapshotFlushQueue.class);
        persistence = mock(SnapshotPersistenceService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        SnapshotProperties properties = new SnapshotProperties();
        SnapshotFlushBackoffPolicy backoff = new SnapshotFlushBackoffPolicy(properties, () -> 0.5);
        SnapshotFlushMetrics metrics = new SnapshotFlushMetrics(new SimpleMeterRegistry());
        coordinator = new SnapshotFlushCoordinator(
            queue, persistence, new ExamAnswerInputValidator(), objectMapper,
            backoff, metrics, Runnable::run
        );
        claim = new SnapshotFlushClaim("1:2", "lease-1");
        when(queue.claimBatch()).thenReturn(new SnapshotFlushClaimBatch(List.of(claim), 0));
        when(queue.backlog()).thenReturn(new SnapshotFlushBacklog(0, 0, 0));
    }

    @Test
    void persistsClaimedSnapshotAndAcknowledgesMatchingVersion() throws Exception {
        when(queue.read(claim)).thenReturn(new SnapshotFlushRead(true, payload(100L), 100L));
        when(persistence.persistDraft(eq(1L), eq(2L), any(), eq(100L))).thenReturn(100L);
        when(queue.acknowledge(claim, 100L)).thenReturn(1L);

        coordinator.flushDue();

        verify(persistence).persistDraft(eq(1L), eq(2L), any(), eq(100L));
        verify(queue).acknowledge(claim, 100L);
        verify(queue, never()).markFailure(any(), eq(100L), any(), any(), anyLong());
    }

    @Test
    void quarantinesDeterministicallyInvalidPayload() {
        when(queue.read(claim)).thenReturn(new SnapshotFlushRead(true, "not-json", 100L));
        when(queue.nextAttempt(claim.member())).thenReturn(1);
        when(queue.markFailure(eq(claim), eq(100L), any(), eq(SnapshotFailureMode.POISON), anyLong()))
            .thenReturn(new SnapshotFailureResult(true, false, true, 1));

        coordinator.flushDue();

        verify(queue).markFailure(
            eq(claim), eq(100L), any(), eq(SnapshotFailureMode.POISON), anyLong()
        );
        verify(persistence, never()).persistDraft(any(), any(), any(), anyLong());
    }

    @Test
    void retriesDatabaseFailureWithoutQuarantining() throws Exception {
        when(queue.read(claim)).thenReturn(new SnapshotFlushRead(true, payload(100L), 100L));
        when(persistence.persistDraft(eq(1L), eq(2L), any(), eq(100L)))
            .thenThrow(new DataRetrievalFailureException("mysql offline"));
        when(queue.nextAttempt(claim.member())).thenReturn(2);
        when(queue.markFailure(eq(claim), eq(100L), any(), eq(SnapshotFailureMode.TRANSIENT), anyLong()))
            .thenReturn(new SnapshotFailureResult(true, false, false, 2));

        coordinator.flushDue();

        verify(queue).markFailure(
            eq(claim), eq(100L), any(), eq(SnapshotFailureMode.TRANSIENT), eq(10_000L)
        );
        verify(queue, never()).acknowledge(claim, 100L);
    }

    @Test
    void missingLegacyPayloadOnlyClearsCurrentLease() {
        when(queue.read(claim)).thenReturn(SnapshotFlushRead.missing());
        when(queue.acknowledgeMissing(claim)).thenReturn(true);

        coordinator.flushDue();

        verify(queue).acknowledgeMissing(claim);
        verify(persistence, never()).persistDraft(any(), any(), any(), anyLong());
    }

    private String payload(long version) throws Exception {
        AnswerPayload answer = new AnswerPayload();
        answer.setQuestionId(11L);
        answer.setAnswerText("A");
        return objectMapper.writeValueAsString(
            new SnapshotPayload(1L, 2L, List.of(answer), version, version, "2026-07-13T10:00:00")
        );
    }
}
