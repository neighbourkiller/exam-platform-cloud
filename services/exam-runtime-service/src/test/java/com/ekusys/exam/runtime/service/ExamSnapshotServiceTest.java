package com.ekusys.exam.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.SnapshotAckView;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.runtime.config.SnapshotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExamSnapshotServiceTest {
    private SnapshotFlushQueue queue;
    private SnapshotPersistenceService persistence;
    private SnapshotDraftPayloadService draftPayloads;
    private ObjectMapper objectMapper;
    private SnapshotProperties properties;
    private ExamSnapshotService service;

    @BeforeEach
    void setUp() {
        queue = mock(SnapshotFlushQueue.class);
        persistence = mock(SnapshotPersistenceService.class);
        draftPayloads = mock(SnapshotDraftPayloadService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        properties = new SnapshotProperties();
        properties.setTtlHours(48L);
        service = new ExamSnapshotService(queue, objectMapper, properties, persistence, draftPayloads, null);
    }

    @Test
    void savesNewerSnapshotWithoutSynchronousDatabaseTouch() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 6, 10, 0);
        SnapshotRequest request = request(100L, "A");
        when(draftPayloads.accept(9L, 1L, 2L, request)).thenReturn(accepted(receivedAt, request));

        SnapshotAckView ack = service.save(
            1L, 2L, 9L, receivedAt.plusHours(2), receivedAt, request
        );

        assertEquals(100L, ack.getSnapshotVersion());
        verify(queue).save(eq(1L), eq(2L), eq(100L), anyString(), eq(180_000_000L));
        verify(persistence, never()).touchActiveSession(any(), any());
    }

    @Test
    void rejectsStaleSnapshotWithoutUpdatingRedis() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 6, 10, 0);
        SnapshotRequest request = request(100L, "old");
        when(draftPayloads.accept(9L, 1L, 2L, request)).thenReturn(
            new SnapshotDraftPayloadService.Acceptance(
                false, 2L, 120L, receivedAt, receivedAt.plusHours(2), null
            )
        );

        SnapshotAckView ack = service.save(
            1L, 2L, 9L, receivedAt.plusHours(2), receivedAt, request
        );

        assertEquals(120L, ack.getSnapshotVersion());
        assertEquals(false, ack.getAccepted());
        verify(queue, never()).save(any(), any(), anyLong(), anyString(), anyLong());
        verify(persistence, never()).touchActiveSession(any(), any());
    }

    @Test
    void durableMySqlAcceptanceSurvivesRedisCacheFailure() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 6, 10, 0);
        SnapshotRequest request = request(100L, "A");
        when(queue.save(eq(1L), eq(2L), eq(100L), anyString(), eq(180_000_000L)))
            .thenThrow(new RedisConnectionFailureException("offline"));
        when(draftPayloads.accept(9L, 1L, 2L, request)).thenReturn(accepted(receivedAt, request));

        SnapshotAckView ack = service.save(
            1L, 2L, 9L, receivedAt.plusHours(2), receivedAt, request
        );

        assertEquals(100L, ack.getSnapshotVersion());
        verify(persistence, never()).persistFallback(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void recoveryChoosesNewerRedisDraft() throws Exception {
        LocalDateTime redisUpdatedAt = LocalDateTime.of(2026, 7, 6, 10, 5);
        SnapshotDraft database = new SnapshotDraft(
            Map.of(11L, "database"), 90L, LocalDateTime.of(2026, 7, 6, 10, 0)
        );
        SnapshotPersistenceService.SubmissionDraftMetadata metadata =
            new SnapshotPersistenceService.SubmissionDraftMetadata(9L, 90L, true);
        when(draftPayloads.loadLatest(1L, 2L)).thenReturn(null);
        when(persistence.loadDraftMetadata(1L, 2L)).thenReturn(metadata);
        when(persistence.loadDraft(metadata)).thenReturn(database);
        when(queue.loadPayload(1L, 2L)).thenReturn(objectMapper.writeValueAsString(Map.of(
            "examId", 1L,
            "studentId", 2L,
            "answers", List.of(Map.of("questionId", 11L, "answerText", "redis")),
            "clientTimestamp", 100L,
            "snapshotVersion", 100L,
            "serverReceivedAt", redisUpdatedAt.toString()
        )));

        SnapshotDraft draft = service.loadLatestDraft(1L, 2L);

        assertEquals(100L, draft.version());
        assertEquals("redis", draft.answers().get(11L));
        assertEquals(redisUpdatedAt, draft.updatedAt());
    }

    @Test
    void cleanupRunsOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.clearAfterCommit(1L, 2L);

            verify(queue, never()).clear(1L, 2L);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCommit()
            );
            verify(queue, times(1)).clear(1L, 2L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void clearBlockedThreeSecondsDoesNotBlockCallingThread() throws Exception {
        java.util.concurrent.Executor asyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch blockLatch = new java.util.concurrent.CountDownLatch(1);

        org.mockito.Mockito.doAnswer(invocation -> {
            blockLatch.await(3, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).when(queue).clear(1L, 2L);

        ExamSnapshotService asyncService = new ExamSnapshotService(
            queue, new ObjectMapper(), properties, persistence, draftPayloads, asyncExecutor
        );

        long start = System.currentTimeMillis();
        asyncService.clearAfterCommit(1L, 2L);
        long elapsed = System.currentTimeMillis() - start;

        org.assertj.core.api.Assertions.assertThat(elapsed).isLessThan(500);
        blockLatch.countDown();
    }

    @Test
    void clearQueueSaturationDropsGracefullyWithoutThrowing() {
        java.util.concurrent.Executor rejectingExecutor = command -> {
            throw new RuntimeException("Queue saturated");
        };
        ExamSnapshotService rejectingService = new ExamSnapshotService(
            queue, new ObjectMapper(), properties, persistence, draftPayloads, rejectingExecutor
        );

        // Must not throw to caller
        rejectingService.clearAfterCommit(1L, 2L);
    }

    private SnapshotRequest request(long version, String answerText) {
        AnswerPayload answer = new AnswerPayload();
        answer.setQuestionId(11L);
        answer.setAnswerText(answerText);
        SnapshotRequest request = new SnapshotRequest();
        request.setAnswers(List.of(answer));
        request.setClientTimestamp(version);
        request.setSnapshotVersion(version);
        return request;
    }

    private SnapshotDraftPayloadService.Acceptance accepted(LocalDateTime receivedAt,
                                                              SnapshotRequest request) {
        return new SnapshotDraftPayloadService.Acceptance(
            true, 1L, request.getSnapshotVersion(), receivedAt, receivedAt.plusHours(2),
            new SnapshotDraft(Map.of(11L, request.getAnswers().getFirst().getAnswerText()), 1L, receivedAt)
        );
    }
}
