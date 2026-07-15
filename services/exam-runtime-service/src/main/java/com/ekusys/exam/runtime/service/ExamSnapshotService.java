package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.SnapshotAckView;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.runtime.config.SnapshotProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ExamSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(ExamSnapshotService.class);

    private final SnapshotFlushQueue queue;
    private final ObjectMapper objectMapper;
    private final SnapshotProperties properties;
    private final SnapshotPersistenceService persistence;

    public ExamSnapshotService(SnapshotFlushQueue queue, ObjectMapper objectMapper,
                               SnapshotProperties properties, SnapshotPersistenceService persistence) {
        this.queue = queue;
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
            result = queue.save(
                examId, studentId, version, json, resolveTtl(deadline, receivedAt).toMillis()
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
        return ack(request, receivedAt, storedVersion);
    }

    public SnapshotDraft loadLatestDraft(Long examId, Long studentId) {
        SnapshotDraft persisted = persistence.loadDraft(examId, studentId);
        try {
            String json = queue.loadPayload(examId, studentId);
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

    private void clear(Long examId, Long studentId) {
        try {
            queue.clear(examId, studentId);
        } catch (DataAccessException exception) {
            log.warn("Redis snapshot cleanup failed: examId={}, studentId={}", examId, studentId, exception);
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

}
