package com.ekusys.exam.runtime.entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExamEntryOperationalMetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExamEntryOperationalMetricsScheduler.class);

    private final JdbcTemplate jdbc;
    private final ExamEntryMetrics metrics;

    public ExamEntryOperationalMetricsScheduler(JdbcTemplate jdbc, ExamEntryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.exam-entry.metrics-refresh-interval-ms:10000}")
    public void refresh() {
        try {
            OperationalCounts counts = jdbc.queryForObject(
                """
                    select coalesce(sum(provisioning_status='PROVISIONING'),0) provisioning_count,
                           coalesce(sum(case when provisioning_status='PROVISIONING'
                               then greatest(candidate_count-prepared_count,0) else 0 end),0) pending_candidates,
                           coalesce(sum(provisioning_status='FAILED'),0) failed_count
                      from runtime_exam_definition
                    """,
                (rs, rowNum) -> new OperationalCounts(
                    rs.getLong("provisioning_count"), rs.getLong("pending_candidates"),
                    rs.getLong("failed_count")
                )
            );
            Long outboxPending = jdbc.queryForObject(
                "select count(*) from outbox_event where status='PENDING'",
                Long.class
            );
            if (counts != null) {
                metrics.operationalSnapshot(
                    counts.provisioning(), counts.pendingCandidates(), counts.failed(),
                    outboxPending == null ? 0L : outboxPending
                );
            }
        } catch (RuntimeException exception) {
            log.debug("Failed to refresh exam entry operational metrics", exception);
        }
    }

    private record OperationalCounts(long provisioning, long pendingCandidates, long failed) {
    }
}
