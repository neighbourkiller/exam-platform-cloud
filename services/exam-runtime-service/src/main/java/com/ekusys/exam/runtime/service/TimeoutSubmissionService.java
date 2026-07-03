package com.ekusys.exam.runtime.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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

    public TimeoutSubmissionService(TimeoutSessionMapper mapper, RuntimeOutboxService outbox,
                                    TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.transactionTemplate = transactionTemplate;
    }

    public int processShard(int shardIndex, int shardTotal) {
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
        mapper.createSubmission(IdWorker.getId(), row.examId(), row.studentId());
        outbox.submissionAccepted(mapper.findSubmissionId(row.examId(), row.studentId()));
        mapper.markSubmitted(row.id());
        return true;
    }

    @Transactional
    public boolean submitExpired(Long sessionId, Long examId, Long studentId) {
        return submitOne(new TimeoutSessionRow(sessionId, examId, studentId, null));
    }
}
