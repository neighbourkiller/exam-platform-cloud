package com.ekusys.exam.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExamSnapshotServiceTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SnapshotPersistenceService persistence;
    private ObjectMapper objectMapper;
    private ExamSnapshotService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        persistence = mock(SnapshotPersistenceService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        SnapshotProperties properties = new SnapshotProperties();
        properties.setTtlHours(48L);
        service = new ExamSnapshotService(redis, objectMapper, properties, persistence);
    }

    @Test
    void savesNewerSnapshotWithVersionAndDeadlineBasedTtl() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 6, 10, 0);
        SnapshotRequest request = request(100L, "A");
        when(redis.execute(
            any(DefaultRedisScript.class), anyList(), eq("100"), anyString(), eq("180000000")
        )).thenReturn(100L);
        when(persistence.touchActiveSession(9L, receivedAt)).thenReturn(1);

        SnapshotAckView ack = service.save(
            1L, 2L, 9L, receivedAt.plusHours(2), receivedAt, request
        );

        assertEquals(100L, ack.getSnapshotVersion());
        verify(redis).execute(
            any(DefaultRedisScript.class),
            eq(List.of("exam:snapshot:1:2", "exam:snapshot-version:1:2")),
            eq("100"), anyString(), eq("180000000")
        );
        verify(persistence).touchActiveSession(9L, receivedAt);
    }

    @Test
    void rejectsStaleSnapshotWithoutTouchingSession() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 6, 10, 0);
        SnapshotRequest request = request(100L, "old");
        when(redis.execute(
            any(DefaultRedisScript.class), anyList(), eq("100"), anyString(), anyString()
        )).thenReturn(-120L);

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
        when(redis.execute(
            any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()
        )).thenThrow(new RedisConnectionFailureException("offline"));
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
        when(persistence.loadDraft(1L, 2L)).thenReturn(database);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("exam:snapshot:1:2")).thenReturn(objectMapper.writeValueAsString(Map.of(
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
    void flushDeletesPayloadOnlyWhenPersistedVersionCoversIt() throws Exception {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 6, 10, 5);
        String json = objectMapper.writeValueAsString(Map.of(
            "examId", 1L,
            "studentId", 2L,
            "answers", List.of(Map.of("questionId", 11L, "answerText", "A")),
            "clientTimestamp", 100L,
            "snapshotVersion", 100L,
            "serverReceivedAt", updatedAt.toString()
        ));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("exam:snapshot:1:2")).thenReturn(json);
        when(persistence.persistDraft(eq(1L), eq(2L), anyList(), eq(100L))).thenReturn(100L);

        service.flushKey("exam:snapshot:1:2");

        verify(redis).execute(
            any(DefaultRedisScript.class),
            eq(List.of("exam:snapshot:1:2", "exam:snapshot-version:1:2")),
            eq("100")
        );
    }

    @Test
    void cleanupRunsOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.clearAfterCommit(1L, 2L);

            verify(redis, never()).delete(anyList());
            TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCommit()
            );
            verify(redis, times(1)).delete(
                List.of("exam:snapshot:1:2", "exam:snapshot-version:1:2")
            );
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
