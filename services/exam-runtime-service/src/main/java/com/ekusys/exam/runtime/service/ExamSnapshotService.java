package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.SnapshotAckView;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.runtime.config.SnapshotProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ExamSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(ExamSnapshotService.class);
    private static final DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[2])
        local incoming = tonumber(ARGV[1])
        local currentNumber = tonumber(current)
        if currentNumber and currentNumber >= incoming then
            return -currentNumber
        end
        redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
        redis.call('SET', KEYS[2], tostring(incoming), 'PX', ARGV[3])
        return incoming
        """, Long.class);
    private static final DefaultRedisScript<Long> DELETE_IF_VERSION_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[2])
        if current and tostring(current) == tostring(ARGV[1]) then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SnapshotProperties properties;
    private final SnapshotPersistenceService persistence;

    public ExamSnapshotService(StringRedisTemplate redis, ObjectMapper objectMapper,
                               SnapshotProperties properties, SnapshotPersistenceService persistence) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.persistence = persistence;
    }

    public SnapshotAckView save(Long examId, Long studentId, Long sessionId,
                                LocalDateTime deadline, LocalDateTime receivedAt,
                                SnapshotRequest request) {
        long version = resolveVersion(request, receivedAt);
        SnapshotPayload payload = new SnapshotPayload(
            examId, studentId, request.getAnswers(), request.getClientTimestamp(), version,
            receivedAt.toString()
        );
        String json = serialize(payload);
        Long result;
        try {
            result = redis.execute(
                SAVE_SCRIPT,
                List.of(snapshotKey(examId, studentId), versionKey(examId, studentId)),
                String.valueOf(version), json, String.valueOf(resolveTtl(deadline, receivedAt).toMillis())
            );
        } catch (DataAccessException exception) {
            log.warn("Redis snapshot unavailable, fallback to MySQL: examId={}, studentId={}, version={}",
                examId, studentId, version, exception);
            long storedVersion = persistence.persistFallback(
                sessionId, examId, studentId, request.getAnswers(), version, receivedAt
            );
            if (storedVersion < 0) {
                throw new BusinessException("考试会话已结束");
            }
            return ack(request, receivedAt, storedVersion);
        }
        if (result == null) {
            log.warn("Redis snapshot script returned no result, fallback to MySQL: examId={}, studentId={}, version={}",
                examId, studentId, version);
            long storedVersion = persistence.persistFallback(
                sessionId, examId, studentId, request.getAnswers(), version, receivedAt
            );
            if (storedVersion < 0) {
                throw new BusinessException("考试会话已结束");
            }
            return ack(request, receivedAt, storedVersion);
        }
        long storedVersion = Math.abs(result);
        if (result > 0 && persistence.touchActiveSession(sessionId, receivedAt) != 1) {
            deletePayloadIfVersion(examId, studentId, version);
            throw new BusinessException("考试会话已结束");
        }
        return ack(request, receivedAt, storedVersion);
    }

    public SnapshotDraft loadLatestDraft(Long examId, Long studentId) {
        SnapshotDraft persisted = persistence.loadDraft(examId, studentId);
        try {
            String json = redis.opsForValue().get(snapshotKey(examId, studentId));
            if (json == null || json.isBlank()) {
                return persisted;
            }
            SnapshotPayload payload = objectMapper.readValue(json, SnapshotPayload.class);
            if (!examId.equals(payload.examId()) || !studentId.equals(payload.studentId())) {
                log.warn("Ignore mismatched snapshot payload: examId={}, studentId={}", examId, studentId);
                return persisted;
            }
            if (payload.snapshotVersion() <= persisted.version()) {
                return persisted;
            }
            return new SnapshotDraft(
                toAnswerMap(payload.answers()), payload.snapshotVersion(), parseReceivedAt(payload.serverReceivedAt())
            );
        } catch (DataAccessException exception) {
            log.warn("Redis snapshot load failed, use MySQL draft: examId={}, studentId={}",
                examId, studentId, exception);
            return persisted;
        } catch (JsonProcessingException exception) {
            log.error("Invalid Redis snapshot payload: examId={}, studentId={}", examId, studentId, exception);
            return persisted;
        }
    }

    public void flushAll() {
        Set<String> keys;
        try {
            keys = findSnapshotKeys();
        } catch (DataAccessException exception) {
            log.warn("Redis snapshot scan failed", exception);
            return;
        }
        for (String key : keys) {
            flushKey(key);
        }
    }

    public void clearAfterCommit(Long examId, Long studentId) {
        Runnable cleanup = () -> clear(examId, studentId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    void flushKey(String key) {
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return;
        }
        try {
            Long examId = Long.valueOf(parts[2]);
            Long studentId = Long.valueOf(parts[3]);
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return;
            }
            SnapshotPayload payload = objectMapper.readValue(json, SnapshotPayload.class);
            long persistedVersion = persistence.persistDraft(
                examId, studentId, payload.answers(), payload.snapshotVersion()
            );
            if (persistedVersion < 0 || persistedVersion >= payload.snapshotVersion()) {
                deletePayloadIfVersion(examId, studentId, payload.snapshotVersion());
            }
        } catch (DataAccessException exception) {
            log.warn("Snapshot flush deferred because Redis is unavailable: key={}", key, exception);
        } catch (Exception exception) {
            log.error("Snapshot flush failed: key={}", key, exception);
        }
    }

    private Set<String> findSnapshotKeys() {
        StringRedisSerializer serializer = new StringRedisSerializer(StandardCharsets.UTF_8);
        Set<String> keys = redis.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new java.util.LinkedHashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match("exam:snapshot:*").count(200).build();
            try (var cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    String key = serializer.deserialize(cursor.next());
                    if (key != null && !key.isBlank()) {
                        result.add(key);
                    }
                }
            }
            return result;
        });
        return keys == null ? Set.of() : keys;
    }

    private void clear(Long examId, Long studentId) {
        try {
            redis.delete(List.of(snapshotKey(examId, studentId), versionKey(examId, studentId)));
        } catch (DataAccessException exception) {
            log.warn("Redis snapshot cleanup failed: examId={}, studentId={}", examId, studentId, exception);
        }
    }

    private void deletePayloadIfVersion(Long examId, Long studentId, long version) {
        try {
            redis.execute(
                DELETE_IF_VERSION_SCRIPT,
                List.of(snapshotKey(examId, studentId), versionKey(examId, studentId)),
                String.valueOf(version)
            );
        } catch (DataAccessException exception) {
            log.warn("Redis snapshot conditional cleanup failed: examId={}, studentId={}, version={}",
                examId, studentId, version, exception);
        }
    }

    private Duration resolveTtl(LocalDateTime deadline, LocalDateTime now) {
        Duration retention = Duration.ofHours(Math.max(1L, properties.getTtlHours()));
        if (deadline == null || !deadline.isAfter(now)) {
            return retention;
        }
        return Duration.between(now, deadline).plus(retention);
    }

    private long resolveVersion(SnapshotRequest request, LocalDateTime receivedAt) {
        if (request.getSnapshotVersion() != null && request.getSnapshotVersion() > 0) {
            return request.getSnapshotVersion();
        }
        if (request.getClientTimestamp() != null && request.getClientTimestamp() > 0) {
            return request.getClientTimestamp();
        }
        return java.sql.Timestamp.valueOf(receivedAt).getTime();
    }

    private String serialize(SnapshotPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("快照序列化失败");
        }
    }

    private SnapshotAckView ack(SnapshotRequest request, LocalDateTime receivedAt, long version) {
        return SnapshotAckView.builder()
            .serverReceivedAt(receivedAt)
            .clientTimestamp(request.getClientTimestamp())
            .snapshotVersion(version)
            .build();
    }

    private Map<Long, String> toAnswerMap(List<AnswerPayload> answers) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (AnswerPayload answer : answers) {
            result.put(answer.getQuestionId(), answer.getAnswerText() == null ? "" : answer.getAnswerText());
        }
        return Map.copyOf(result);
    }

    private LocalDateTime parseReceivedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException exception) {
            log.warn("Invalid snapshot serverReceivedAt: {}", value);
            return null;
        }
    }

    private String snapshotKey(Long examId, Long studentId) {
        return "exam:snapshot:" + examId + ":" + studentId;
    }

    private String versionKey(Long examId, Long studentId) {
        return "exam:snapshot-version:" + examId + ":" + studentId;
    }

    private record SnapshotPayload(Long examId, Long studentId, List<AnswerPayload> answers,
                                   Long clientTimestamp, long snapshotVersion,
                                   String serverReceivedAt) {
    }
}
