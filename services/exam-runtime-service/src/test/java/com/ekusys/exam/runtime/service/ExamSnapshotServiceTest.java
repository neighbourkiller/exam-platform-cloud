package com.ekusys.exam.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
    private ObjectMapper objectMapper;
    private ExamSnapshotService service;

    @BeforeEach
    void setUp() {
        queue = mock(SnapshotFlushQueue.class);
        persistence = mock(SnapshotPersistenceService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        SnapshotProperties properties = new SnapshotProperties();
        properties.setTtlHours(48L);
        service = new ExamSnapshotService(queue, objectMapper, properties, persistence);
    }

    @Test
    void savesNewerSnapshotWithoutSynchronousDatabaseTouch() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 6, 10, 0);
        SnapshotRequest request = request(100L, "A");
        when(queue.save(eq(1L), eq(2L), eq(100L), anyString(), eq(180_000_000L))).thenReturn(100L);

        SnapshotAckView ack = service.save(
            1L, 2L, 9L, receivedAt.plusHours(2), receivedAt, request
        );

        assertEquals(100L, ack.getSnapshotVersion());
        verify(queue).save(eq(1L), eq(2L), eq(100L), anyString(), eq(180_000_000L));
        verify(persistence, never()).touchActiveSession(any(), any());
    }

    @Test
    void rejectsStaleSnapshotWithoutTouchingSession() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 6, 10, 0);
        SnapshotRequest request = request(100L, "old");
        when(queue.save(eq(1L), eq(2L), eq(100L), anyString(), eq(180_000_000L))).thenReturn(-120L);

        SnapshotAckView ack = service.save(
            1L, 2L, 9L, receivedAt.plusHours(2), receivedAt, request
        );

        assertEquals(120L, ack.getSnapshotVersion());
        verify(persistence, never()).touchActiveSession(any(), any());
    }

    @Test
    void fallsBackToMySqlWhenRedisIsUnavailable() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 6, 10, 0);
        SnapshotRequest request = request(100L, "A");
        when(queue.save(eq(1L), eq(2L), eq(100L), anyString(), eq(180_000_000L)))
            .thenThrow(new RedisConnectionFailureException("offline"));
        when(persistence.persistFallback(
            9L, 1L, 2L, request.getAnswers(), 100L, receivedAt
        )).thenReturn(100L);

        SnapshotAckView ack = service.save(
            1L, 2L, 9L, receivedAt.plusHours(2), receivedAt, request
        );

        assertEquals(100L, ack.getSnapshotVersion());
        verify(persistence).persistFallback(
            9L, 1L, 2L, request.getAnswers(), 100L, receivedAt
        );
    }

    @Test
    void recoveryChoosesNewerRedisDraft() throws Exception {
        LocalDateTime redisUpdatedAt = LocalDateTime.of(2026, 7, 6, 10, 5);
        SnapshotDraft database = new SnapshotDraft(
            Map.of(11L, "database"), 90L, LocalDateTime.of(2026, 7, 6, 10, 0)
        );
        SnapshotPersistenceService.SubmissionDraftMetadata metadata =
            new SnapshotPersistenceService.SubmissionDraftMetadata(9L, 90L, true);
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
}
