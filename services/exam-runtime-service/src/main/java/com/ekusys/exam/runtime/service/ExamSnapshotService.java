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
    private final SnapshotDraftPayloadService draftPayloads;
    private final java.util.concurrent.Executor postCommitExecutor;

    public ExamSnapshotService(SnapshotFlushQueue queue, ObjectMapper objectMapper,
                               SnapshotProperties properties, SnapshotPersistenceService persistence,
                               SnapshotDraftPayloadService draftPayloads,
                               @org.springframework.lang.Nullable @org.springframework.beans.factory.annotation.Qualifier("finalizationPostCommitExecutor") java.util.concurrent.Executor postCommitExecutor) {
        this.queue = queue;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.persistence = persistence;
        this.draftPayloads = draftPayloads;
        this.postCommitExecutor = postCommitExecutor != null ? postCommitExecutor : Runnable::run;
    }

    public SnapshotAckView save(Long examId, Long studentId, Long sessionId,
                                LocalDateTime deadline, LocalDateTime receivedAt,
                                SnapshotRequest request) {
        SnapshotDraftPayloadService.Acceptance acceptance = draftPayloads.accept(
            sessionId, examId, studentId, request
        );
        long version = acceptance.storedClientSequence();
        if (!acceptance.accepted()) {
            return ack(request, acceptance);
        }
        SnapshotPayload payload = new SnapshotPayload(
            examId, studentId, request.getAnswers(), request.getClientTimestamp(), version,
            acceptance.acceptedAt().toString()
        );
        String json = serialize(payload);
        try {
            queue.save(
                examId, studentId, version, json,
                resolveTtl(acceptance.deadline(), acceptance.acceptedAt()).toMillis()
            );
        } catch (DataAccessException exception) {
            log.warn("Redis snapshot cache unavailable after durable MySQL accept: examId={}, studentId={}, version={}",
                examId, studentId, version, exception);
        }
        return ack(request, acceptance);
    }

    public SnapshotDraft loadLatestDraft(Long examId, Long studentId) {
        return loadLatestDraft(examId, studentId, null);
    }

    public SnapshotDraft loadLatestDraft(Long examId, Long studentId, Long submissionId) {
        SnapshotDraft durable = submissionId != null
            ? draftPayloads.loadLatestBySubmissionId(submissionId)
            : draftPayloads.loadLatest(examId, studentId);
        if (durable != null) {
            return durable;
        }
        SnapshotPersistenceService.SubmissionDraftMetadata metadata =
            persistence.loadDraftMetadata(examId, studentId);
        try {
            String json = queue.loadPayload(examId, studentId);
            if (json == null || json.isBlank()) {
                return persistence.loadDraft(metadata);
            }
            SnapshotPayload payload = objectMapper.readValue(json, SnapshotPayload.class);
            if (!examId.equals(payload.examId()) || !studentId.equals(payload.studentId())) {
                log.warn("Ignore mismatched snapshot payload: examId={}, studentId={}", examId, studentId);
                return persistence.loadDraft(metadata);
            }
            if (payload.snapshotVersion() <= metadata.version()) {
                return persistence.loadDraft(metadata);
            }
            return new SnapshotDraft(
                toAnswerMap(payload.answers()), payload.snapshotVersion(), parseReceivedAt(payload.serverReceivedAt())
            );
        } catch (DataAccessException exception) {
            log.warn("Redis snapshot load failed, use MySQL draft: examId={}, studentId={}",
                examId, studentId, exception);
            return persistence.loadDraft(metadata);
        } catch (JsonProcessingException exception) {
            log.error("Invalid Redis snapshot payload: examId={}, studentId={}", examId, studentId, exception);
            return persistence.loadDraft(metadata);
        }
    }

    public void clearAfterCommit(Long examId, Long studentId) {
        Runnable task = () -> {
            try {
                postCommitExecutor.execute(() -> clear(examId, studentId));
            } catch (Exception e) {
                log.warn("Failed to dispatch snapshot clear: examId={}, studentId={}", examId, studentId, e);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
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

    private String serialize(SnapshotPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("快照序列化失败");
        }
    }

    private SnapshotAckView ack(SnapshotRequest request,
                                SnapshotDraftPayloadService.Acceptance acceptance) {
        return SnapshotAckView.builder()
            .serverReceivedAt(acceptance.acceptedAt())
            .clientTimestamp(request.getClientTimestamp())
            .snapshotVersion(acceptance.storedClientSequence())
            .accepted(acceptance.accepted())
            .serverRevision(acceptance.serverRevision())
            .storedClientSequence(acceptance.storedClientSequence())
            .serverEpochMs(RuntimeTime.epochMillis(acceptance.acceptedAt()))
            .deadlineEpochMs(RuntimeTime.epochMillis(acceptance.deadline()))
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
