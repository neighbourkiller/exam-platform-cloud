package com.ekusys.exam.runtime.service;

import com.ekusys.exam.exam.dto.SubmissionStatusView;
import com.ekusys.exam.runtime.messaging.SubmissionAcceptedReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SubmissionStatusProjectionService {
    private static final Logger log = LoggerFactory.getLogger(SubmissionStatusProjectionService.class);
    private static final String KEY_PREFIX = "exam:submission-status:";
    private static final String INFLIGHT_KEY_PREFIX = "exam:submission-status:inflight:";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration INFLIGHT_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TimeoutSubmissionMetrics metrics;
    private final Executor postCommitExecutor;

    public SubmissionStatusProjectionService(StringRedisTemplate redis,
                                             ObjectMapper objectMapper,
                                             TimeoutSubmissionMetrics metrics,
                                             @org.springframework.lang.Nullable @Qualifier("finalizationPostCommitExecutor") Executor postCommitExecutor) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.postCommitExecutor = postCommitExecutor != null ? postCommitExecutor : Runnable::run;
    }

    public void recordSubmittedAfterCommit(SubmissionAcceptedReceipt receipt) {
        if (receipt == null) {
            return;
        }
        recordSubmittedAfterCommit(
            receipt.examId(), receipt.studentId(), receipt.submissionId(),
            receipt.finalSnapshotVersion(), receipt.submittedAt(), receipt.finalizedAt(),
            Boolean.TRUE.equals(receipt.timeoutSubmit())
        );
    }

    public void recordSubmittedAfterCommit(Long examId, Long studentId, Long submissionId,
                                           Long finalSnapshotVersion, LocalDateTime submittedAt,
                                           LocalDateTime finalizedAt, boolean timeoutSubmit) {
        Runnable task = () -> submitAsyncProjection(
            examId, studentId, submissionId, finalSnapshotVersion, submittedAt, finalizedAt, timeoutSubmit
        );
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

    public void refreshProjectionAsync(Long examId, Long studentId, SubmissionStatusView view) {
        if (view == null || !"SUBMITTED".equals(view.getSessionStatus())) {
            return;
        }
        submitAsyncProjection(
            examId, studentId, view.getSubmissionId(),
            view.getFinalSnapshotVersion(), view.getSubmittedAt(), view.getFinalizedAt(),
            Boolean.TRUE.equals(view.getTimeoutSubmit())
        );
    }

    private void submitAsyncProjection(Long examId, Long studentId, Long submissionId,
                                       Long finalSnapshotVersion, LocalDateTime submittedAt,
                                       LocalDateTime finalizedAt, boolean timeoutSubmit) {
        if (examId == null || studentId == null || submissionId == null
            || submittedAt == null || finalizedAt == null) {
            metrics.recordProjection("invalid_receipt");
            log.warn("Skip incomplete submission status projection: examId={}, studentId={}, submissionId={}",
                examId, studentId, submissionId);
            return;
        }
        try {
            postCommitExecutor.execute(() -> {
                try {
                    String key = cacheKey(examId, studentId);
                    FinalizedStatusProjection projection = new FinalizedStatusProjection(
                        submissionId, "SUBMITTED", "PROCESSING", timeoutSubmit,
                        "DONE", finalSnapshotVersion,
                        submittedAt,
                        finalizedAt
                    );
                    String json = objectMapper.writeValueAsString(projection);
                    long sRedis = System.nanoTime();
                    Boolean written = redis.opsForValue().setIfAbsent(key, json, TTL);
                    metrics.recordStage("post_commit_redis", Duration.ofNanos(System.nanoTime() - sRedis));
                    if (Boolean.TRUE.equals(written)) {
                        metrics.recordProjection("written");
                        try {
                            redis.delete(inflightKey(examId, studentId));
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception e) {
                    metrics.recordProjection("write_failed");
                    log.warn("Failed to write submission status projection to Redis: examId={}, studentId={}",
                        examId, studentId, e);
                }
            });
        } catch (Exception e) {
            metrics.recordProjection("dropped");
            log.warn("Failed to submit projection task: examId={}, studentId={}", examId, studentId, e);
        }
    }

    public SubmissionStatusView getInflightProjection(Long examId, Long studentId) {
        try {
            String key = inflightKey(examId, studentId);
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            SubmissionStatusView view = objectMapper.readValue(json, SubmissionStatusView.class);
            view.setServerEpochMs(System.currentTimeMillis());
            return view;
        } catch (Exception e) {
            return null;
        }
    }

    public void recordInflightProjection(Long examId, Long studentId, SubmissionStatusView view) {
        if (view == null || Boolean.TRUE.equals(view.getRuntimeFinalized())) {
            return;
        }
        try {
            String key = inflightKey(examId, studentId);
            String json = objectMapper.writeValueAsString(view);
            redis.opsForValue().set(key, json, INFLIGHT_TTL);
        } catch (Exception ignored) {
        }
    }

    public SubmissionStatusView getCachedProjection(Long examId, Long studentId) {
        try {
            String key = cacheKey(examId, studentId);
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                metrics.recordProjection("miss");
                return null;
            }
            FinalizedStatusProjection projection = objectMapper.readValue(json, FinalizedStatusProjection.class);
            if (!"SUBMITTED".equals(projection.sessionStatus())) {
                metrics.recordProjection("miss");
                return null;
            }
            metrics.recordProjection("hit");
            long serverEpochMs = System.currentTimeMillis();
            return SubmissionStatusView.builder()
                .submissionId(projection.submissionId())
                .sessionStatus(projection.sessionStatus())
                .submissionStatus(projection.submissionStatus())
                .timeoutSubmit(projection.timeoutSubmit())
                .submittedAt(projection.submittedAt())
                .timeoutTaskStatus(projection.timeoutTaskStatus())
                .phase("RUNTIME_FINALIZED")
                .runtimeFinalized(true)
                .serverEpochMs(serverEpochMs)
                .retryable(false)
                .finalSnapshotVersion(projection.finalSnapshotVersion())
                .finalizedAt(projection.finalizedAt())
                .build();
        } catch (Exception e) {
            metrics.recordProjection("read_error");
            log.warn("Failed to read submission status projection from Redis: examId={}, studentId={}",
                examId, studentId, e);
            return null;
        }
    }

    private String cacheKey(Long examId, Long studentId) {
        return KEY_PREFIX + examId + ":" + studentId;
    }

    private String inflightKey(Long examId, Long studentId) {
        return INFLIGHT_KEY_PREFIX + examId + ":" + studentId;
    }

    public record FinalizedStatusProjection(
        Long submissionId,
        String sessionStatus,
        String submissionStatus,
        Boolean timeoutSubmit,
        String timeoutTaskStatus,
        Long finalSnapshotVersion,
        LocalDateTime submittedAt,
        LocalDateTime finalizedAt
    ) {
    }
}
