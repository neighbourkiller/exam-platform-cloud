package com.ekusys.exam.common.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboxRepository {
    private static final int MAX_ERROR_LENGTH = 1_000;

    private final JdbcTemplate jdbc;
    private final OutboxProperties properties;
    private final OutboxBackoffPolicy backoffPolicy;
    private final TransactionTemplate requiresNew;

    public OutboxRepository(JdbcTemplate jdbc, OutboxProperties properties,
                            OutboxBackoffPolicy backoffPolicy,
                            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.backoffPolicy = backoffPolicy;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public OutboxClaimBatch claimBatch() {
        return requiresNew.execute(status -> {
            RecoveryCounts recovery = recoverExpiredLeases();
            List<PendingRow> pending = jdbc.query(
                "select id,event_type,payload_json,retry_count from outbox_event "
                    + "where status='PENDING' "
                    + "and (next_retry_time is null or next_retry_time<=current_timestamp(3)) "
                    + "order by created_at,id limit ? for update skip locked",
                (rs, rowNum) -> new PendingRow(
                    rs.getString("id"), rs.getString("event_type"),
                    rs.getString("payload_json"), rs.getInt("retry_count")
                ),
                properties.safeBatchSize()
            );
            List<OutboxRow> claimed = new ArrayList<>(pending.size());
            long leaseMicros = properties.safeLeaseDurationMs() * 1_000L;
            for (PendingRow row : pending) {
                String leaseToken = UUID.randomUUID().toString();
                int updated = jdbc.update(
                    "update outbox_event set status='SENDING',lease_token=?,"
                        + "lease_until=timestampadd(microsecond,?,current_timestamp(3)) "
                        + "where id=? and status='PENDING'",
                    leaseToken, leaseMicros, row.id()
                );
                if (updated == 1) {
                    claimed.add(new OutboxRow(
                        row.id(), row.eventType(), row.payload(), row.retryCount(), leaseToken
                    ));
                }
            }
            return new OutboxClaimBatch(List.copyOf(claimed), recovery.retried(), recovery.failed());
        });
    }

    public boolean markPublished(OutboxRow row) {
        return jdbc.update(
            "update outbox_event set status='PUBLISHED',published_at=current_timestamp(3),"
                + "next_retry_time=null,lease_token=null,lease_until=null,last_error=null,failed_at=null "
                + "where id=? and status='SENDING' and lease_token=?",
            row.id(), row.leaseToken()
        ) == 1;
    }

    public OutboxFailureResult markFailedAttempt(OutboxRow row, Throwable failure) {
        int failureCount = row.retryCount() + 1;
        boolean permanent = failureCount >= properties.safeMaxAttempts();
        String targetStatus = permanent ? "FAILED" : "PENDING";
        long delayMicros = backoffPolicy.delayMillis(failureCount) * 1_000L;
        int updated = jdbc.update(
            "update outbox_event set status=?,retry_count=?,"
                + "next_retry_time=case when ?='FAILED' then null "
                + "else timestampadd(microsecond,?,current_timestamp(3)) end,"
                + "lease_token=null,lease_until=null,last_error=?,"
                + "failed_at=case when ?='FAILED' then current_timestamp(3) else null end "
                + "where id=? and status='SENDING' and lease_token=?",
            targetStatus, failureCount, targetStatus, delayMicros, errorMessage(failure),
            targetStatus, row.id(), row.leaseToken()
        );
        return updated == 1
            ? new OutboxFailureResult(true, permanent, failureCount)
            : OutboxFailureResult.stale(failureCount);
    }

    public int cleanupPublished() {
        Integer deleted = requiresNew.execute(status -> jdbc.update(
            "delete from outbox_event where status='PUBLISHED' "
                + "and published_at<timestampadd(microsecond,?,current_timestamp(3)) "
                + "order by published_at,id limit ?",
            -Math.max(1, properties.getPublishedRetentionMs()) * 1_000L,
            properties.safeCleanupBatchSize()
        ));
        return deleted == null ? 0 : deleted;
    }

    private RecoveryCounts recoverExpiredLeases() {
        List<LeasedRow> expired = jdbc.query(
            "select id,event_type,retry_count,lease_token from outbox_event "
                + "where status='SENDING' and lease_until<=current_timestamp(3) "
                + "order by lease_until,id limit ? for update skip locked",
            (rs, rowNum) -> new LeasedRow(
                rs.getString("id"), rs.getString("event_type"),
                rs.getInt("retry_count"), rs.getString("lease_token")
            ),
            properties.safeBatchSize()
        );
        int retried = 0;
        int failed = 0;
        for (LeasedRow row : expired) {
            int failureCount = row.retryCount() + 1;
            boolean permanent = failureCount >= properties.safeMaxAttempts();
            String targetStatus = permanent ? "FAILED" : "PENDING";
            long delayMicros = backoffPolicy.delayMillis(failureCount) * 1_000L;
            int updated = jdbc.update(
                "update outbox_event set status=?,retry_count=?,"
                    + "next_retry_time=case when ?='FAILED' then null "
                    + "else timestampadd(microsecond,?,current_timestamp(3)) end,"
                    + "lease_token=null,lease_until=null,last_error='publisher lease expired',"
                    + "failed_at=case when ?='FAILED' then current_timestamp(3) else null end "
                    + "where id=? and status='SENDING' and lease_token=?",
                targetStatus, failureCount, targetStatus, delayMicros, targetStatus,
                row.id(), row.leaseToken()
            );
            if (updated == 1) {
                if (permanent) {
                    failed++;
                } else {
                    retried++;
                }
            }
        }
        return new RecoveryCounts(retried, failed);
    }

    private String errorMessage(Throwable failure) {
        String message = failure == null ? "unknown publisher failure" : failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure == null ? "unknown publisher failure" : failure.getClass().getSimpleName();
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private record PendingRow(String id, String eventType, String payload, int retryCount) {
    }

    private record LeasedRow(String id, String eventType, int retryCount, String leaseToken) {
    }

    private record RecoveryCounts(int retried, int failed) {
    }
}
