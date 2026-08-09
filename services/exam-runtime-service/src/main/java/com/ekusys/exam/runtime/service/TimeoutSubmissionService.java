package com.ekusys.exam.runtime.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.runtime.config.TimeoutSubmissionProperties;
import com.ekusys.exam.runtime.repository.TimeoutSessionMapper;
import com.ekusys.exam.runtime.repository.TimeoutSessionRow;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TimeoutSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(TimeoutSubmissionService.class);
    private static final int BATCH_SIZE = 200;
    private final TimeoutSessionMapper mapper;
    private final RuntimeOutboxService outbox;
    private final TransactionTemplate transactionTemplate;
    private final ExamSnapshotService snapshotService;
    private final SnapshotPersistenceService snapshotPersistence;
    private final TimeoutSubmissionProperties properties;
    private final TimeoutSubmissionCoordinator coordinator;

    public TimeoutSubmissionService(TimeoutSessionMapper mapper, RuntimeOutboxService outbox,
                                    TransactionTemplate transactionTemplate,
                                    ExamSnapshotService snapshotService,
                                    SnapshotPersistenceService snapshotPersistence,
                                    TimeoutSubmissionProperties properties,
                                    TimeoutSubmissionCoordinator coordinator) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.transactionTemplate = transactionTemplate;
        this.snapshotService = snapshotService;
        this.snapshotPersistence = snapshotPersistence;
        this.properties = properties;
        this.coordinator = coordinator;
    }

    public int processShard(int shardIndex, int shardTotal) {
        if (properties.isEnabled()) {
            return coordinator.processDue();
        }
        int processed = 0;
        List<TimeoutSessionRow> rows = mapper.findClaimable(shardIndex, shardTotal, BATCH_SIZE);
        for (TimeoutSessionRow row : rows) {
            try {
                Boolean submitted = transactionTemplate.execute(status -> submitOne(row));
                if (Boolean.TRUE.equals(submitted)) {
                    processed++;
                }
            } catch (RuntimeException ex) {
                log.error("Timeout submission failed: sessionId={}, examId={}, studentId={}",
                    row.id(), row.examId(), row.studentId(), ex);
            }
        }
        return processed;
    }

    public boolean submitOne(TimeoutSessionRow row) {
        if (mapper.claim(row.id()) != 1) {
            return false;
        }
        SnapshotDraft draft = snapshotService.loadLatestDraft(row.examId(), row.studentId());
        mapper.createSubmission(IdWorker.getId(), row.examId(), row.studentId());
        Long submissionId = mapper.findSubmissionId(row.examId(), row.studentId());
        snapshotPersistence.replaceFinalAnswers(submissionId, draft.answers(), "TIMEOUT_SUBMIT");
        outbox.submissionAccepted(submissionId);
        mapper.markSubmitted(row.id());
        snapshotService.clearAfterCommit(row.examId(), row.studentId());
        return true;
    }

    @Transactional
    public boolean submitExpired(Long sessionId, Long examId, Long studentId) {
        if (properties.isEnabled()) {
            coordinator.ensureExpiredTask(sessionId, examId, studentId, null);
            return true;
        }
        return submitOne(new TimeoutSessionRow(sessionId, examId, studentId, null));
    }

    public String acceptExpired(Long sessionId, Long examId, Long studentId,
                                java.time.LocalDateTime deadline) {
        if (properties.isEnabled()) {
            return coordinator.ensureExpiredTask(sessionId, examId, studentId, deadline);
        }
        submitExpired(sessionId, examId, studentId);
        return "PROCESSING";
    }

    public boolean isV2Enabled() {
        return properties.isEnabled();
    }
}
