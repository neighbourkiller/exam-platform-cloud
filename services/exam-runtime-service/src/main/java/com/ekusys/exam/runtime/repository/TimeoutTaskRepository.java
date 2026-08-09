package com.ekusys.exam.runtime.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TimeoutTaskRepository {
    private final JdbcTemplate jdbc;

    public TimeoutTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTask(Long sessionId, Long examId, Long studentId, LocalDateTime dueAt) {
        jdbc.update(
            """
                insert ignore into submission_timeout_task(
                    id,session_id,exam_id,student_id,due_at,status,created_at,updated_at
                ) values(?,?,?,?,?,'PENDING',current_timestamp(3),current_timestamp(3))
                """,
            sessionId, sessionId, examId, studentId, dueAt
        );
    }

    public int reconcileMissingTasks(int limit) {
        return jdbc.update(
            """
                insert ignore into submission_timeout_task(
                    id,session_id,exam_id,student_id,due_at,status,created_at,updated_at
                )
                select s.id,s.id,s.exam_id,s.student_id,s.deadline_time,'PENDING',
                       current_timestamp(3),current_timestamp(3)
                  from exam_session s
                  left join submission_timeout_task t on t.session_id=s.id
                 where t.id is null
                   and s.status in ('ANSWERING','AUTO_SUBMITTING')
                   and s.deadline_time is not null
                 order by s.id
                 limit ?
                """,
            limit
        );
    }

    public List<TaskCandidate> lockClaimable(int limit) {
        int safeLimit = Math.max(1, limit);
        RowMapper<TaskCandidate> mapper = (rs, rowNum) -> new TaskCandidate(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getLong("exam_id"),
            rs.getLong("student_id"),
            rs.getObject("due_at", LocalDateTime.class),
            rs.getString("status"),
            rs.getInt("attempt_count"),
            rs.getObject("lease_until", LocalDateTime.class)
        );
        List<TaskCandidate> candidates = new ArrayList<>(safeLimit);
        candidates.addAll(jdbc.query(
            """
                select id,session_id,exam_id,student_id,due_at,status,attempt_count,lease_until
                  from submission_timeout_task
                 where status='PROCESSING' and lease_until<current_timestamp(3)
                 order by lease_until,id
                 limit ?
                 for update skip locked
                """,
            mapper,
            safeLimit
        ));
        int remaining = safeLimit - candidates.size();
        if (remaining > 0) {
            candidates.addAll(jdbc.query(
                """
                    select id,session_id,exam_id,student_id,due_at,status,attempt_count,lease_until
                      from submission_timeout_task
                     where status='PENDING'
                       and due_at<=current_timestamp(3)
                       and (next_retry_at is null or next_retry_at<=current_timestamp(3))
                     order by due_at,id
                     limit ?
                     for update skip locked
                    """,
                mapper,
                remaining
            ));
        }
        return List.copyOf(candidates);
    }

    public int markProcessing(Long taskId, String claimToken, long leaseMs) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set status='PROCESSING',claim_token=?,
                       lease_until=timestampadd(microsecond,?,current_timestamp(3)),
                       attempt_count=attempt_count+1,next_retry_at=null,last_error=null,
                       updated_at=current_timestamp(3)
                 where id=?
                """,
            claimToken, leaseMs * 1_000L, taskId
        );
    }

    public int claimSessionForTimeout(Long sessionId) {
        return jdbc.update(
            """
                update exam_session
                   set status='AUTO_SUBMITTING',claim_time=current_timestamp(3),
                       update_time=current_timestamp(3)
                 where id=?
                   and ((status='ANSWERING' and deadline_time<=current_timestamp(3))
                     or status='AUTO_SUBMITTING')
                """,
            sessionId
        );
    }

    public TaskRow lockBySession(Long sessionId) {
        List<TaskRow> rows = jdbc.query(
            """
                select id,session_id,exam_id,student_id,due_at,status,claim_token,
                       lease_until,attempt_count,next_retry_at
                  from submission_timeout_task
                 where session_id=?
                 for update
                """,
            (rs, rowNum) -> new TaskRow(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getLong("exam_id"),
                rs.getLong("student_id"),
                rs.getObject("due_at", LocalDateTime.class),
                rs.getString("status"),
                rs.getString("claim_token"),
                rs.getObject("lease_until", LocalDateTime.class),
                rs.getInt("attempt_count"),
                rs.getObject("next_retry_at", LocalDateTime.class)
            ),
            sessionId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public TaskRow lockById(Long taskId) {
        List<TaskRow> rows = jdbc.query(
            """
                select id,session_id,exam_id,student_id,due_at,status,claim_token,
                       lease_until,attempt_count,next_retry_at
                  from submission_timeout_task
                 where id=?
                 for update
                """,
            (rs, rowNum) -> new TaskRow(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getLong("exam_id"),
                rs.getLong("student_id"),
                rs.getObject("due_at", LocalDateTime.class),
                rs.getString("status"),
                rs.getString("claim_token"),
                rs.getObject("lease_until", LocalDateTime.class),
                rs.getInt("attempt_count"),
                rs.getObject("next_retry_at", LocalDateTime.class)
            ),
            taskId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public SessionState lockSession(Long sessionId) {
        List<SessionState> rows = jdbc.query(
            """
                select id,exam_id,student_id,status,deadline_time
                  from exam_session
                 where id=?
                 for update
                """,
            (rs, rowNum) -> new SessionState(
                rs.getLong("id"),
                rs.getLong("exam_id"),
                rs.getLong("student_id"),
                rs.getString("status"),
                rs.getObject("deadline_time", LocalDateTime.class)
            ),
            sessionId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int claimManualSession(Long sessionId, String clientId, String leaseToken) {
        return jdbc.update(
            """
                update exam_session
                   set status='AUTO_SUBMITTING',claim_time=current_timestamp(3),
                       update_time=current_timestamp(3)
                 where id=? and status='ANSWERING'
                   and deadline_time>current_timestamp(3)
                   and active_client_id=? and active_client_token=?
                """,
            sessionId, clientId, leaseToken
        );
    }

    public int markSessionSubmitted(Long sessionId) {
        return jdbc.update(
            """
                update exam_session
                   set status='SUBMITTED',end_time=current_timestamp(3),claim_time=null,
                       active_client_id=null,active_client_token=null,
                       active_client_lease_until=null,active_client_last_seen=null,
                       update_time=current_timestamp(3)
                 where id=? and status='AUTO_SUBMITTING'
                """,
            sessionId
        );
    }

    public int markDone(Long taskId, String claimToken) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set status='DONE',claim_token=null,lease_until=null,next_retry_at=null,
                       last_error=null,completed_at=current_timestamp(3),updated_at=current_timestamp(3)
                 where id=? and status='PROCESSING' and claim_token=?
                   and lease_until>current_timestamp(3)
                """,
            taskId, claimToken
        );
    }

    public int markDoneLocked(Long taskId) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set status='DONE',claim_token=null,lease_until=null,next_retry_at=null,
                       last_error=null,completed_at=current_timestamp(3),updated_at=current_timestamp(3)
                 where id=? and status<>'DONE'
                """,
            taskId
        );
    }

    public int expeditePendingLocked(Long taskId) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set due_at=least(due_at,current_timestamp(3)),next_retry_at=null,
                       updated_at=current_timestamp(3)
                 where id=? and status='PENDING'
                """,
            taskId
        );
    }

    public int markFailure(Long taskId, String claimToken, LocalDateTime nextRetryAt,
                           int maxAttempts, String lastError) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set status=case when attempt_count>=? then 'FAILED' else 'PENDING' end,
                       next_retry_at=case when attempt_count>=? then null else ? end,
                       last_error=?,claim_token=null,lease_until=null,updated_at=current_timestamp(3)
                 where id=? and status='PROCESSING' and claim_token=?
                   and lease_until>current_timestamp(3)
                """,
            maxAttempts, maxAttempts, nextRetryAt, abbreviate(lastError), taskId, claimToken
        );
    }

    public int markInvalidStateFailed(Long taskId, String claimToken, String lastError) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set status='FAILED',next_retry_at=null,last_error=?,claim_token=null,
                       lease_until=null,updated_at=current_timestamp(3)
                 where id=? and status='PROCESSING' and claim_token=?
                   and lease_until>current_timestamp(3)
                """,
            abbreviate(lastError), taskId, claimToken
        );
    }

    public int markAttemptsExhaustedLocked(Long taskId, int maxAttempts, String lastError) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set status='FAILED',next_retry_at=null,last_error=?,claim_token=null,
                       lease_until=null,updated_at=current_timestamp(3)
                 where id=? and status in ('PENDING','PROCESSING') and attempt_count>=?
                """,
            abbreviate(lastError), taskId, maxAttempts
        );
    }

    public TimeoutSubmissionBacklog backlog() {
        return jdbc.queryForObject(
            """
                select coalesce(sum(status='PENDING'),0) pending_count,
                       coalesce(sum(status='PROCESSING'),0) processing_count,
                       coalesce(sum(status='FAILED'),0) failed_count,
                       coalesce(max(case
                           when status in ('PENDING','PROCESSING') and due_at<current_timestamp(3)
                           then timestampdiff(microsecond,due_at,current_timestamp(3)) div 1000
                           else 0 end),0) oldest_overdue_ms
                  from submission_timeout_task
                """,
            (rs, rowNum) -> new TimeoutSubmissionBacklog(
                rs.getLong("pending_count"),
                rs.getLong("processing_count"),
                rs.getLong("failed_count"),
                rs.getLong("oldest_overdue_ms")
            )
        );
    }

    private String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    public record TaskCandidate(Long id, Long sessionId, Long examId, Long studentId,
                                LocalDateTime dueAt, String status, int attemptCount,
                                LocalDateTime leaseUntil) {
        public boolean recoveredLease() {
            return "PROCESSING".equals(status);
        }
    }

    public record TaskRow(Long id, Long sessionId, Long examId, Long studentId,
                          LocalDateTime dueAt, String status, String claimToken,
                          LocalDateTime leaseUntil, int attemptCount,
                          LocalDateTime nextRetryAt) {
        public boolean ownedBy(String token) {
            return "PROCESSING".equals(status) && token != null && token.equals(claimToken);
        }
    }

    public record SessionState(Long id, Long examId, Long studentId,
                               String status, LocalDateTime deadline) {
    }

    public record TimeoutSubmissionBacklog(long pending, long processing,
                                            long failed, long oldestOverdueMs) {
    }
}
