package com.ekusys.exam.runtime.entry;

import com.ekusys.exam.exam.dto.ExamClientLeaseView;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.service.ExamClientLeaseService;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExamActivationTransactionService {
    private final JdbcTemplate jdbc;
    private final RuntimeExamDefinitionRepository definitions;
    private final ExamClientLeaseService leases;
    private final RuntimeOutboxService outbox;
    private final ExamEntryMetrics metrics;
    private final TransactionTemplate transactions;

    public ExamActivationTransactionService(
        JdbcTemplate jdbc,
        RuntimeExamDefinitionRepository definitions,
        ExamClientLeaseService leases,
        RuntimeOutboxService outbox,
        ExamEntryMetrics metrics,
        @Qualifier("examEntryActivationTransactionTemplate") TransactionTemplate transactions
    ) {
        this.jdbc = jdbc;
        this.definitions = definitions;
        this.leases = leases;
        this.outbox = outbox;
        this.metrics = metrics;
        this.transactions = transactions;
    }

    public ActivationResult activate(RuntimeExamDefinition definition, Long studentId,
                                     String clientId) {
        long startedNanos = System.nanoTime();
        try {
            ActivationResult result = transactions.execute(status -> execute(
                definition, studentId, clientId
            ));
            if (result == null) {
                throw new IllegalStateException("激活事务未返回结果");
            }
            metrics.activationTransaction(System.nanoTime() - startedNanos, result.outcome());
            return result;
        } catch (RuntimeException exception) {
            metrics.activationTransaction(System.nanoTime() - startedNanos, "FAILED");
            throw exception;
        }
    }

    private ActivationResult execute(RuntimeExamDefinition definition, Long studentId,
                                     String clientId) {
        LocalDateTime now = dbNow();
        RuntimeEntrySession session = definitions.findSession(definition.examId(), studentId);
        if (session == null) {
            throw new ExamEntryException(
                HttpStatus.FORBIDDEN, "EXAM_NOT_CANDIDATE", "你不在本场考试名单中"
            );
        }
        if (!"PREPARED".equals(session.status())) {
            return new ActivationResult(session, now, false, session.status());
        }
        LocalDateTime deadline = calculateDeadline(
            now, definition.durationMinutes(), definition.endTime()
        );
        if (!now.isBefore(deadline)) {
            throw new ExamEntryException(HttpStatus.GONE, "EXAM_ENTRY_CLOSED", "本场考试已结束");
        }
        ExamClientLeaseView lease = leases.createInitialLease(clientId, now);
        LocalDateTime leaseUntil = lease.getLeaseExpiresAt().isBefore(deadline)
            ? lease.getLeaseExpiresAt() : deadline;
        int updated = jdbc.update(
            """
                update exam_session
                   set status='ANSWERING',start_time=?,deadline_time=?,claim_time=null,
                       active_client_id=?,active_client_token=?,active_client_lease_until=?,
                       active_client_last_seen=?,update_time=current_timestamp(3)
                 where id=? and status='PREPARED'
                """,
            now, deadline, clientId, lease.getLeaseToken(), leaseUntil, now, session.id()
        );
        if (updated == 0) {
            RuntimeEntrySession current = definitions.findSession(definition.examId(), studentId);
            if (current != null && !"PREPARED".equals(current.status())) {
                return new ActivationResult(current, now, false, current.status());
            }
            throw new ExamEntryException(
                HttpStatus.SERVICE_UNAVAILABLE, "EXAM_ENTRY_BUSY", "考试会话正在激活，请稍后重试", 100L
            );
        }
        int taskUpdated = jdbc.update(
            """
                update submission_timeout_task
                   set status='PENDING',due_at=?,next_retry_at=null,last_error=null,
                       updated_at=current_timestamp(3)
                 where session_id=? and status='WAITING'
                """,
            deadline, session.id()
        );
        if (taskUpdated != 1) {
            throw new IllegalStateException("预创建超时任务不存在: sessionId=" + session.id());
        }
        outbox.sessionStarted(session.id(), definition.examId(), studentId, now);
        RuntimeEntrySession activated = new RuntimeEntrySession(
            session.id(), definition.examId(), studentId, "ANSWERING", now, deadline,
            clientId, lease.getLeaseToken(), leaseUntil, session.submissionId(), session.draftVersion()
        );
        return new ActivationResult(activated, now, true, "STARTED");
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
    }

    static LocalDateTime calculateDeadline(LocalDateTime activatedAt, int durationMinutes,
                                           LocalDateTime examEnd) {
        LocalDateTime durationDeadline = activatedAt.plusMinutes(durationMinutes);
        return durationDeadline.isBefore(examEnd) ? durationDeadline : examEnd;
    }

    public record ActivationResult(RuntimeEntrySession session, LocalDateTime dbNow,
                                   boolean newlyStarted, String outcome) {
    }
}
