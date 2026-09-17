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
        ensureTask(sessionId, examId, studentId, dueAt, null);
    }

    public void ensureTask(Long sessionId, Long examId, Long studentId, LocalDateTime dueAt, Long submissionId) {
        if (submissionId != null) {
            jdbc.update(
                """
                    insert ignore into submission_timeout_task(
                        id,session_id,exam_id,student_id,submission_id,due_at,status,created_at,updated_at
                    ) values(?,?,?,?,?,?,'PENDING',current_timestamp(3),current_timestamp(3))
                    """,
                sessionId, sessionId, examId, studentId, submissionId, dueAt
            );
        } else {
            jdbc.update(
                """
                    insert ignore into submission_timeout_task(
                        id,session_id,exam_id,student_id,submission_id,due_at,status,created_at,updated_at
                    )
                    select ?,?,?,?,s.id,?,'PENDING',current_timestamp(3),current_timestamp(3)
                      from exam_session sess
                      left join submission s on s.exam_id=sess.exam_id and s.student_id=sess.student_id
                     where sess.id=?
                    """,
                sessionId, sessionId, examId, studentId, dueAt, sessionId
            );
        }
    }

    public boolean existsBySession(Long sessionId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from submission_timeout_task where session_id=?",
            Integer.class,
            sessionId
        );
        return count != null && count > 0;
    }

    public TimeoutHandoffState findHandoffState(Long sessionId) {
        List<TimeoutHandoffState> rows = jdbc.query(
            """
                select s.exam_id,s.student_id,s.status session_status,s.deadline_time,
                       t.id task_id,t.status task_status,t.due_at,
                       current_timestamp(3) db_now
                  from exam_session s
                  left join submission_timeout_task t on t.session_id=s.id
                 where s.id=?
                """,
            (rs, rowNum) -> new TimeoutHandoffState(
                rs.getLong("exam_id"),
                rs.getLong("student_id"),
                rs.getString("session_status"),
                rs.getObject("deadline_time", LocalDateTime.class),
                rs.getObject("task_id", Long.class),
                rs.getString("task_status"),
                rs.getObject("due_at", LocalDateTime.class),
                rs.getObject("db_now", LocalDateTime.class)
            ),
            sessionId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int repairTaskFromSession(Long sessionId) {
        return jdbc.update(
            """
                insert ignore into submission_timeout_task(
                    id,session_id,exam_id,student_id,submission_id,due_at,status,created_at,updated_at
                )
                select sess.id,sess.id,sess.exam_id,sess.student_id,s.id,sess.deadline_time,'PENDING',
                       current_timestamp(3),current_timestamp(3)
                  from exam_session sess
                  left join submission s on s.exam_id=sess.exam_id and s.student_id=sess.student_id
                 where sess.id=? and sess.deadline_time is not null
                """,
            sessionId
        );
    }

    public long missingTaskCount() {
        Long count = jdbc.queryForObject(
            """
                select count(*)
                  from exam_session s
                  left join submission_timeout_task t on t.session_id=s.id
                 where t.id is null
                   and s.status in ('ANSWERING','AUTO_SUBMITTING')
                   and s.deadline_time is not null
                """,
            Long.class
        );
        return count == null ? 0L : count;
    }

    public ClaimSelection lockClaimable(int shardIndex, int shardTotal, int limit, long crossShardDelayMs) {
        validateShard(shardIndex, shardTotal);
        int safeLimit = Math.max(1, limit);
        RowMapper<TaskCandidate> mapper = (rs, rowNum) -> new TaskCandidate(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getLong("exam_id"),
            rs.getLong("student_id"),
            rs.getObject("submission_id", Long.class),
            rs.getObject("due_at", LocalDateTime.class),
            rs.getString("status"),
            rs.getInt("attempt_count"),
            rs.getObject("lease_until", LocalDateTime.class)
        );
        List<TaskCandidate> selected = new ArrayList<>(safeLimit);
        int ownPending = 0;
        int crossPending = 0;
        int ownLeaseRecovered = 0;
        int crossLeaseRecovered = 0;

        // 阶段1：全局恢复过期租约，不按分片过滤
        selected.addAll(jdbc.query(
            """
                select id,session_id,exam_id,student_id,submission_id,due_at,status,attempt_count,lease_until
                  from submission_timeout_task
                 where status='PROCESSING' and lease_until<current_timestamp(3)
                 order by lease_until,id
                 limit ?
                 for update skip locked
                """,
            mapper,
            safeLimit
        ));
        for (TaskCandidate candidate : selected) {
            if (Math.floorMod(candidate.id(), shardTotal) == shardIndex) {
                ownLeaseRecovered += 1;
            } else {
                crossLeaseRecovered += 1;
            }
        }

        boolean multiShard = shardTotal > 1;
        int remaining = safeLimit - selected.size();
        int ownQuota = remaining;
        if (multiShard) {
            // 为跨片补领预留 ceil(remaining/2)，本阶段最多领取其余数量
            int crossReserve = (remaining + 1) / 2;
            ownQuota = remaining - crossReserve;
        }

        // 阶段2：优先领取本片已到 available_at 的 PENDING
        if (ownQuota > 0) {
            List<TaskCandidate> own = jdbc.query(
                """
                    select id,session_id,exam_id,student_id,submission_id,due_at,status,attempt_count,lease_until
                      from submission_timeout_task force index (idx_timeout_task_claim)
                     where status='PENDING'
                       and available_at<=current_timestamp(3)
                       and mod(id, ?) = ?
                     order by available_at,id
                     limit ?
                     for update skip locked
                    """,
                mapper,
                shardTotal, shardIndex, ownQuota
            );
            selected.addAll(own);
            ownPending += own.size();
        }

        // 阶段3：跨片补领，要求 available_at 已超过兜底等待时间
        remaining = safeLimit - selected.size();
        if (multiShard && remaining > 0) {
            List<TaskCandidate> cross = jdbc.query(
                """
                    select id,session_id,exam_id,student_id,submission_id,due_at,status,attempt_count,lease_until
                      from submission_timeout_task force index (idx_timeout_task_claim)
                     where status='PENDING'
                       and available_at<=timestampadd(microsecond, -?, current_timestamp(3))
                       and mod(id, ?) <> ?
                     order by available_at,id
                     limit ?
                     for update skip locked
                    """,
                mapper,
                crossShardDelayMs * 1_000L, shardTotal, shardIndex, remaining
            );
            selected.addAll(cross);
            crossPending += cross.size();
        }

        // 阶段4：本片补足，排除本事务已选中的任务（自身行锁不会阻止重复选中）；
        // 阶段2 配额为零且阶段3 无候选时同样必须执行，保证本片任务不被跨片预留饿死
        remaining = safeLimit - selected.size();
        if (multiShard && remaining > 0) {
            String fillSql;
            Object[] params;
            if (selected.isEmpty()) {
                fillSql = """
                    select id,session_id,exam_id,student_id,submission_id,due_at,status,attempt_count,lease_until
                      from submission_timeout_task force index (idx_timeout_task_claim)
                     where status='PENDING'
                       and available_at<=current_timestamp(3)
                       and mod(id, ?) = ?
                     order by available_at,id
                     limit ?
                     for update skip locked
                    """;
                params = new Object[] {shardTotal, shardIndex, remaining};
            } else {
                String placeholders = String.join(",", java.util.Collections.nCopies(selected.size(), "?"));
                fillSql = """
                    select id,session_id,exam_id,student_id,submission_id,due_at,status,attempt_count,lease_until
                      from submission_timeout_task force index (idx_timeout_task_claim)
                     where status='PENDING'
                       and available_at<=current_timestamp(3)
                       and mod(id, ?) = ?
                       and id not in (""" + placeholders + """
                    )
                     order by available_at,id
                     limit ?
                     for update skip locked
                    """;
                params = new Object[2 + selected.size() + 1];
                params[0] = shardTotal;
                params[1] = shardIndex;
                for (int index = 0; index < selected.size(); index += 1) {
                    params[2 + index] = selected.get(index).id();
                }
                params[params.length - 1] = remaining;
            }
            List<TaskCandidate> fill = jdbc.query(fillSql, mapper, params);
            selected.addAll(fill);
            ownPending += fill.size();
        }
        return new ClaimSelection(List.copyOf(selected), ownPending, crossPending, ownLeaseRecovered, crossLeaseRecovered);
    }

    private static void validateShard(int shardIndex, int shardTotal) {
        if (shardTotal < 1 || shardIndex < 0 || shardIndex >= shardTotal) {
            throw new IllegalArgumentException(
                "非法超时交卷分片参数: shardIndex=" + shardIndex + ", shardTotal=" + shardTotal
            );
        }
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

    public int renewLease(Long taskId, String claimToken, long leaseMs) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set lease_until=timestampadd(microsecond,?,current_timestamp(3)),
                       updated_at=current_timestamp(3)
                 where id=? and status='PROCESSING' and claim_token=?
                   and lease_until>current_timestamp(3)
                """,
            leaseMs * 1_000L, taskId, claimToken
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
                select id,session_id,exam_id,student_id,submission_id,due_at,status,claim_token,
                       lease_until,attempt_count,next_retry_at,current_timestamp(3) db_now
                  from submission_timeout_task
                 where session_id=?
                 for update
                """,
            (rs, rowNum) -> new TaskRow(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getLong("exam_id"),
                rs.getLong("student_id"),
                rs.getObject("submission_id", Long.class),
                rs.getObject("due_at", LocalDateTime.class),
                rs.getString("status"),
                rs.getString("claim_token"),
                rs.getObject("lease_until", LocalDateTime.class),
                rs.getInt("attempt_count"),
                rs.getObject("next_retry_at", LocalDateTime.class),
                rs.getObject("db_now", LocalDateTime.class)
            ),
            sessionId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public TaskRow lockById(Long taskId) {
        List<TaskRow> rows = jdbc.query(
            """
                select id,session_id,exam_id,student_id,submission_id,due_at,status,claim_token,
                       lease_until,attempt_count,next_retry_at,current_timestamp(3) db_now
                  from submission_timeout_task
                 where id=?
                 for update
                """,
            (rs, rowNum) -> new TaskRow(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getLong("exam_id"),
                rs.getLong("student_id"),
                rs.getObject("submission_id", Long.class),
                rs.getObject("due_at", LocalDateTime.class),
                rs.getString("status"),
                rs.getString("claim_token"),
                rs.getObject("lease_until", LocalDateTime.class),
                rs.getInt("attempt_count"),
                rs.getObject("next_retry_at", LocalDateTime.class),
                rs.getObject("db_now", LocalDateTime.class)
            ),
            taskId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int backfillSubmissionId(Long taskId, Long submissionId) {
        return jdbc.update(
            "update submission_timeout_task set submission_id=?, updated_at=current_timestamp(3) where id=? and submission_id is null",
            submissionId, taskId
        );
    }

    public long nullSubmissionIdCount() {
        Long count = jdbc.queryForObject(
            "select count(*) from submission_timeout_task where submission_id is null",
            Long.class
        );
        return count == null ? 0L : count;
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
                           int maxAttempts, String lastError, String failureCode,
                           String incidentId) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set status=case when attempt_count>=? then 'FAILED' else 'PENDING' end,
                       next_retry_at=case when attempt_count>=? then null else ? end,
                       last_error=?,failure_code=case when attempt_count>=? then ? else null end,
                       incident_id=case when attempt_count>=? then ? else null end,
                       failed_at=case when attempt_count>=? then current_timestamp(3) else null end,
                       claim_token=null,lease_until=null,updated_at=current_timestamp(3)
                 where id=? and status='PROCESSING' and claim_token=?
                   and lease_until>current_timestamp(3)
                """,
            maxAttempts, maxAttempts, nextRetryAt, abbreviate(lastError),
            maxAttempts, failureCode, maxAttempts, incidentId, maxAttempts,
            taskId, claimToken
        );
    }

    public int markSessionSubmissionFailed(Long sessionId) {
        return jdbc.update(
            """
                update exam_session s
                join submission sub on sub.exam_id=s.exam_id and sub.student_id=s.student_id
                left join submission_final_payload fp on fp.submission_id=sub.id
                   set s.status='SUBMISSION_FAILED',s.claim_time=null,
                       s.end_time=current_timestamp(3),s.active_client_id=null,
                       s.active_client_token=null,s.active_client_lease_until=null,
                       s.active_client_last_seen=null,s.update_time=current_timestamp(3)
                 where s.id=? and s.status='AUTO_SUBMITTING'
                   and sub.status='IN_PROGRESS' and fp.submission_id is null
                   and not exists (
                       select 1 from outbox_event o
                        where o.aggregate_type='SUBMISSION' and o.aggregate_id=cast(sub.id as char)
                          and o.event_type='SubmissionAccepted'
                   )
                """,
            sessionId
        );
    }

    public long inconsistentStateCount() {
        Long count = jdbc.queryForObject(
            """
                select count(*)
                  from submission_timeout_task t
                  join exam_session s on s.id=t.session_id
                  join submission sub on sub.exam_id=s.exam_id and sub.student_id=s.student_id
                 where (t.status='FAILED' and s.status in ('ANSWERING','AUTO_SUBMITTING'))
                    or (s.status='SUBMISSION_FAILED' and t.status<>'FAILED')
                    or (t.status='DONE' and (s.status<>'SUBMITTED' or sub.status='IN_PROGRESS'))
                    or (t.status='PROCESSING' and (t.claim_token is null or t.lease_until is null))
                """,
            Long.class
        );
        return count == null ? 0L : count;
    }

    public int markInvalidStateFailed(Long taskId, String claimToken, String lastError) {
        return jdbc.update(
            """
                update submission_timeout_task
                   set status='FAILED',next_retry_at=null,last_error=?,claim_token=null,
                       lease_until=null,failure_code='INVALID_SESSION_STATE',incident_id=uuid(),
                       failed_at=current_timestamp(3),updated_at=current_timestamp(3)
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
                       lease_until=null,failure_code='LEASE_ATTEMPTS_EXHAUSTED',incident_id=uuid(),
                       failed_at=current_timestamp(3),updated_at=current_timestamp(3)
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

    public record ClaimSelection(List<TaskCandidate> candidates, int ownPending, int crossPending,
                                 int ownLeaseRecovered, int crossLeaseRecovered) {
    }

    public record TaskCandidate(Long id, Long sessionId, Long examId, Long studentId,
                                Long submissionId, LocalDateTime dueAt, String status,
                                int attemptCount, LocalDateTime leaseUntil) {
        public TaskCandidate(Long id, Long sessionId, Long examId, Long studentId,
                             LocalDateTime dueAt, String status, int attemptCount,
                             LocalDateTime leaseUntil) {
            this(id, sessionId, examId, studentId, null, dueAt, status, attemptCount, leaseUntil);
        }

        public boolean recoveredLease() {
            return "PROCESSING".equals(status);
        }
    }

    public record TaskRow(Long id, Long sessionId, Long examId, Long studentId,
                          Long submissionId, LocalDateTime dueAt, String status,
                          String claimToken, LocalDateTime leaseUntil, int attemptCount,
                          LocalDateTime nextRetryAt, LocalDateTime dbNow) {
        public TaskRow(Long id, Long sessionId, Long examId, Long studentId,
                       LocalDateTime dueAt, String status, String claimToken,
                       LocalDateTime leaseUntil, int attemptCount,
                       LocalDateTime nextRetryAt) {
            this(id, sessionId, examId, studentId, null, dueAt, status, claimToken, leaseUntil, attemptCount, nextRetryAt, null);
        }

        public boolean ownedBy(String token) {
            return "PROCESSING".equals(status) && token != null && token.equals(claimToken);
        }
    }

    public record SessionState(Long id, Long examId, Long studentId,
                               String status, LocalDateTime deadline) {
    }

    public record TimeoutHandoffState(Long examId, Long studentId, String sessionStatus,
                                      LocalDateTime deadline, Long taskId, String taskStatus,
                                      LocalDateTime dueAt, LocalDateTime dbNow) {
        public boolean isDurablyDue(Long expectedExamId, Long expectedStudentId) {
            return taskId != null
                && expectedExamId.equals(examId)
                && expectedStudentId.equals(studentId)
                && "ANSWERING".equals(sessionStatus)
                && "PENDING".equals(taskStatus)
                && deadline != null
                && dueAt != null
                && dbNow != null
                && !deadline.isAfter(dbNow)
                && !dueAt.isAfter(dbNow);
        }
    }

    public record TimeoutSubmissionBacklog(long pending, long processing,
                                            long failed, long oldestOverdueMs) {
    }
}
