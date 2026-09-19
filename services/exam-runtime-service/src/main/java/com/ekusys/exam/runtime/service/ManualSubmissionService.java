package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.SessionState;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository.TaskRow;
import com.ekusys.exam.runtime.service.SubmissionFinalPayloadService.EncodedFinalAnswers;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ManualSubmissionService {
    private static final int MAX_LOCK_ATTEMPTS = 3;
    private static final long MIN_RETRY_DELAY_MS = 10L;

    private final TimeoutTaskRepository tasks;
    private final SubmissionFinalPayloadService finalPayloads;
    private final RuntimeOutboxService outbox;
    private final SubmissionStatusProjectionService projectionService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ManualSubmissionService(TimeoutTaskRepository tasks,
                                   SubmissionFinalPayloadService finalPayloads,
                                   RuntimeOutboxService outbox,
                                   SubmissionStatusProjectionService projectionService,
                                   JdbcTemplate jdbc,
                                   TransactionTemplate transactions) {
        this.tasks = tasks;
        this.finalPayloads = finalPayloads;
        this.outbox = outbox;
        this.projectionService = projectionService;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public ManualSubmissionResult submit(Long sessionId, Long examId, Long studentId,
                                         Long submissionId,
                                         SubmitExamRequest request,
                                         EncodedFinalAnswers encoded) {
        repairTaskBeforeFinalization(sessionId);
        for (int attempt = 1; attempt <= MAX_LOCK_ATTEMPTS; attempt++) {
            try {
                ManualSubmissionResult result = transactions.execute(status -> {
                    TaskRow task = tasks.lockBySession(sessionId);
                    if (task == null) {
                        throw new IllegalStateException("超时交卷任务不存在");
                    }
                    if (!examId.equals(task.examId()) || !studentId.equals(task.studentId())) {
                        throw new IllegalStateException("超时交卷任务与考试会话不匹配");
                    }
                    SessionState session = tasks.lockSession(sessionId);
                    if (session == null) {
                        throw new BusinessException("考试会话不存在");
                    }
                    return finalizeLocked(task, session, submissionId, request, encoded);
                });
                if (result == null) {
                    throw new IllegalStateException("主动交卷事务未返回结果");
                }
                return result;
            } catch (PessimisticLockingFailureException exception) {
                if (attempt == MAX_LOCK_ATTEMPTS) {
                    throw exception;
                }
                pauseBeforeRetry(attempt, exception);
            }
        }
        throw new IllegalStateException("主动交卷事务重试状态异常");
    }

    private void repairTaskBeforeFinalization(Long sessionId) {
        if (!tasks.existsBySession(sessionId)) {
            tasks.repairTaskFromSession(sessionId);
        }
    }

    private void pauseBeforeRetry(int attempt, PessimisticLockingFailureException original) {
        long upperBound = MIN_RETRY_DELAY_MS * (1L << attempt);
        long delayMs = ThreadLocalRandom.current().nextLong(MIN_RETRY_DELAY_MS, upperBound + 1L);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            original.addSuppressed(interrupted);
            throw original;
        }
    }

    private ManualSubmissionResult finalizeLocked(TaskRow task, SessionState session,
                                                  Long submissionId, SubmitExamRequest request,
                                                  EncodedFinalAnswers encoded) {
        if ("SUBMITTED".equals(session.status())) {
            completeTaskLocked(task);
            return new ManualSubmissionResult("PROCESSING", false);
        }
        if ("PROCESSING".equals(task.status()) || "AUTO_SUBMITTING".equals(session.status())) {
            return new ManualSubmissionResult("SUBMITTING", false);
        }
        if (!"ANSWERING".equals(session.status())) {
            throw new BusinessException("考试会话已结束");
        }
        if (tasks.claimManualSession(session.id(), request.getClientId(), request.getLeaseToken()) != 1) {
            SessionState latest = tasks.lockSession(session.id());
            if (latest != null && !"ANSWERING".equals(latest.status())) {
                return new ManualSubmissionResult(
                    "SUBMITTED".equals(latest.status()) ? "PROCESSING" : "SUBMITTING",
                    false
                );
            }
            if ("PENDING".equals(task.status()) && tasks.expeditePendingLocked(task.id()) != 1) {
                throw new IllegalStateException("超时交卷任务加速失败");
            }
            if (latest != null && latest.deadline() != null
                && !latest.deadline().isAfter(dbNow())) {
                return new ManualSubmissionResult("SUBMITTING", false);
            }
            throw new BusinessException(
                ExamClientLeaseService.CONFLICT_CODE,
                "考试窗口租约已变化，请勿从多个窗口重复交卷"
            );
        }

        finalPayloads.store(submissionId, "MANUAL", encoded);
        int submissionUpdated = jdbc.update(
            """
                update submission
                   set status='PROCESSING',submitted_at=current_timestamp(3),timeout_submit=0,
                       update_time=current_timestamp(3)
                 where id=? and status='IN_PROGRESS'
                """,
            submissionId
        );
        if (submissionUpdated != 1) {
            throw new IllegalStateException("主动交卷提交记录状态冲突");
        }
        com.ekusys.exam.runtime.messaging.SubmissionAcceptedReceipt receipt = outbox.submissionAccepted(submissionId);
        if (tasks.markSessionSubmitted(session.id()) != 1) {
            throw new IllegalStateException("主动交卷会话状态更新失败");
        }
        completeTaskLocked(task);
        projectionService.recordSubmittedAfterCommit(receipt);
        return new ManualSubmissionResult("PROCESSING", true);
    }

    private void completeTaskLocked(TaskRow task) {
        if (!"DONE".equals(task.status()) && tasks.markDoneLocked(task.id()) != 1) {
            throw new IllegalStateException("交卷任务完成标记失败");
        }
    }

    private java.time.LocalDateTime dbNow() {
        return jdbc.queryForObject("select current_timestamp(3)", java.time.LocalDateTime.class);
    }

    public record ManualSubmissionResult(String status, boolean completedNow) {
    }
}
