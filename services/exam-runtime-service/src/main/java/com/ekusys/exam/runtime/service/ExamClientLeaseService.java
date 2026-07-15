package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.ExamClientLeaseView;
import com.ekusys.exam.runtime.config.ClientLeaseProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ExamClientLeaseService {
    public static final String REQUIRED_CODE = "EXAM_CLIENT_REQUIRED";
    public static final String CONFLICT_CODE = "EXAM_CLIENT_CONFLICT";
    public static final String UNAVAILABLE_CODE = "EXAM_CLIENT_LEASE_UNAVAILABLE";
    private static final int MAX_CLIENT_ID_LENGTH = 128;
    private static final int MAX_TOKEN_LENGTH = 128;
    private static final Logger log = LoggerFactory.getLogger(ExamClientLeaseService.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> RENEW_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 0 then
            return {'0'}
        end
        if redis.call('HGET', KEYS[1], 'clientId') ~= ARGV[1]
            or redis.call('HGET', KEYS[1], 'token') ~= ARGV[2] then
            return {'-1'}
        end
        local deadline = tonumber(redis.call('HGET', KEYS[1], 'deadlineEpochMs'))
        local now = tonumber(ARGV[3])
        if not deadline or deadline <= now then
            redis.call('DEL', KEYS[1])
            return {'-2'}
        end
        local ttl = math.min(tonumber(ARGV[4]), deadline - now)
        redis.call('HSET', KEYS[1], 'lastSeenEpochMs', ARGV[3])
        if ARGV[5] == '1' then
            redis.call('HSET', KEYS[1], 'lastSnapshotEpochMs', ARGV[3])
        end
        local persist = '0'
        local lastPersisted = tonumber(redis.call('HGET', KEYS[1], 'lastPersistedEpochMs'))
        if not lastPersisted or now - lastPersisted >= tonumber(ARGV[6]) then
            redis.call('HSET', KEYS[1], 'lastPersistedEpochMs', ARGV[3])
            persist = '1'
        end
        redis.call('PEXPIRE', KEYS[1], ttl)
        return {
            '1', redis.call('HGET', KEYS[1], 'sessionId'),
            tostring(deadline), tostring(ttl), persist
        }
        """, List.class);

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 1 then
            if redis.call('HGET', KEYS[1], 'clientId') == ARGV[1]
                and redis.call('HGET', KEYS[1], 'token') == ARGV[2] then
                redis.call('PEXPIRE', KEYS[1], ARGV[8])
                return 1
            end
            return 0
        end
        redis.call('HSET', KEYS[1],
            'sessionId', ARGV[3], 'examId', ARGV[4], 'studentId', ARGV[5],
            'clientId', ARGV[1], 'token', ARGV[2], 'deadlineEpochMs', ARGV[6],
            'lastSeenEpochMs', ARGV[7], 'lastPersistedEpochMs', ARGV[7])
        redis.call('PEXPIRE', KEYS[1], ARGV[8])
        return 1
        """, Long.class);

    private static final DefaultRedisScript<Long> DELETE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[1], 'token') == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ClientLeaseProperties properties;

    public ExamClientLeaseService(JdbcTemplate jdbc, StringRedisTemplate redis,
                                  ClientLeaseProperties properties) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.properties = properties;
    }

    public ExamClientLeaseView createInitialLease(String clientId, LocalDateTime now) {
        validateClientId(clientId);
        return newLease(UUID.randomUUID().toString(), now, properties.safeLeaseTimeoutSeconds() * 1_000L);
    }

    public void activateAfterCommit(Long examId, Long studentId, Long sessionId, LocalDateTime deadline,
                                    String clientId, ExamClientLeaseView lease, LocalDateTime now) {
        Runnable activation = () -> activate(
            examId, studentId, sessionId, deadline, clientId, lease.getLeaseToken(), now, true
        );
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            activation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activation.run();
            }
        });
    }

    public ExamClientLeaseView acquire(Long examId, Long studentId, Long sessionId, LocalDateTime deadline,
                                       String clientId, String leaseToken, LocalDateTime now) {
        validateClientId(clientId);
        if (hasText(leaseToken)) {
            validateToken(leaseToken);
            try {
                ExamClientLeaseContext context = renewRedis(
                    examId, studentId, clientId, leaseToken, now, false
                );
                if (context != null) {
                    return context.lease();
                }
            } catch (DataAccessException exception) {
                log.warn("Redis client lease unavailable while resuming; using MySQL: examId={}, studentId={}",
                    examId, studentId, exception);
            }
            ExamClientLeaseView fallback = renewMatchingDatabaseLease(sessionId, clientId, leaseToken, now);
            if (!activate(examId, studentId, sessionId, deadline, clientId, leaseToken, now, true)) {
                throw conflict();
            }
            return fallback;
        }

        String token = UUID.randomUUID().toString();
        long ttlMillis = ttlMillis(deadline, now);
        try {
            if (!activate(examId, studentId, sessionId, deadline, clientId, token, now, false)) {
                throw conflict();
            }
        } catch (DataAccessException exception) {
            log.warn("Redis client lease unavailable; refusing a new client takeover: examId={}, studentId={}",
                examId, studentId, exception);
            throw new BusinessException(UNAVAILABLE_CODE, "考试窗口校验服务暂时不可用，请稍后重试");
        }
        registerRollbackCleanup(examId, studentId, token);
        int updated;
        try {
            updated = jdbc.update(
                """
                    update exam_session
                       set active_client_id=?,active_client_token=?,active_client_lease_until=?,
                           active_client_last_seen=?,update_time=?
                     where id=? and status='ANSWERING'
                       and (active_client_id is null or active_client_token is null
                        or active_client_lease_until is null or active_client_lease_until<=?)
                    """,
                clientId, token, now.plusNanos(ttlMillis * 1_000_000L), now, now, sessionId, now
            );
        } catch (RuntimeException exception) {
            deleteRedisIfToken(examId, studentId, token);
            throw exception;
        }
        if (updated != 1) {
            deleteRedisIfToken(examId, studentId, token);
            throw conflict();
        }
        return newLease(token, now, ttlMillis);
    }

    public ExamClientLeaseContext renew(Long examId, Long studentId, String clientId,
                                        String leaseToken, LocalDateTime now, boolean snapshot) {
        validateClientId(clientId);
        validateToken(leaseToken);
        try {
            ExamClientLeaseContext context = renewRedis(
                examId, studentId, clientId, leaseToken, now, snapshot
            );
            if (context != null) {
                return context;
            }
        } catch (DataAccessException exception) {
            log.warn("Redis client lease unavailable; using MySQL fallback: examId={}, studentId={}",
                examId, studentId, exception);
        }
        DatabaseLeaseRow row = loadMatchingDatabaseLease(examId, studentId, clientId, leaseToken, now);
        ExamClientLeaseView lease = renewMatchingDatabaseLease(row.sessionId(), clientId, leaseToken, now);
        if (!activate(examId, studentId, row.sessionId(), row.deadline(), clientId, leaseToken, now, true)) {
            throw conflict();
        }
        return new ExamClientLeaseContext(row.sessionId(), row.deadline(), now, lease);
    }

    public void requireCurrent(Long sessionId, String clientId, String leaseToken, LocalDateTime now) {
        validateClientId(clientId);
        validateToken(leaseToken);
        int updated = jdbc.update(
            """
                update exam_session
                   set active_client_last_seen=?,active_client_lease_until=?,update_time=?
                 where id=? and status='ANSWERING' and deadline_time>?
                   and active_client_id=? and active_client_token=?
                """,
            now, now.plusSeconds(properties.safeLeaseTimeoutSeconds()), now,
            sessionId, now, clientId, leaseToken
        );
        if (updated != 1) {
            throw conflict();
        }
    }

    public void clearAfterCommit(Long examId, Long studentId, String leaseToken) {
        Runnable cleanup = () -> deleteRedisIfToken(examId, studentId, leaseToken);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    public LocalDateTime lastSnapshot(Long examId, Long studentId) {
        try {
            Object value = redis.opsForHash().get(key(examId, studentId), "lastSnapshotEpochMs");
            if (value == null) {
                return null;
            }
            return localDateTime(Long.parseLong(String.valueOf(value)));
        } catch (DataAccessException | NumberFormatException exception) {
            log.debug("Failed to read live snapshot time: examId={}, studentId={}", examId, studentId, exception);
            return null;
        }
    }

    private ExamClientLeaseContext renewRedis(Long examId, Long studentId, String clientId,
                                              String leaseToken, LocalDateTime now, boolean snapshot) {
        List<?> result = redis.execute(
            RENEW_SCRIPT, List.of(key(examId, studentId)),
            clientId, leaseToken, String.valueOf(epochMillis(now)),
            String.valueOf(properties.safeLeaseTimeoutSeconds() * 1_000L), snapshot ? "1" : "0",
            String.valueOf(properties.safePersistenceIntervalSeconds() * 1_000L)
        );
        if (result == null || result.isEmpty() || "0".equals(asString(result.getFirst()))) {
            return null;
        }
        String code = asString(result.getFirst());
        if ("-1".equals(code)) {
            throw conflict();
        }
        if ("-2".equals(code)) {
            throw new BusinessException(REQUIRED_CODE, "考试作答时间已结束");
        }
        if (!"1".equals(code) || result.size() < 5) {
            throw new BusinessException(UNAVAILABLE_CODE, "考试窗口校验服务返回异常，请稍后重试");
        }
        long deadlineMillis = Long.parseLong(asString(result.get(2)));
        long ttlMillis = Long.parseLong(asString(result.get(3)));
        Long sessionId = Long.valueOf(asString(result.get(1)));
        if ("1".equals(asString(result.get(4)))) {
            persistLiveness(sessionId, clientId, leaseToken, now);
        }
        return new ExamClientLeaseContext(
            sessionId, localDateTime(deadlineMillis), now,
            newLease(leaseToken, now, ttlMillis)
        );
    }

    private boolean activate(Long examId, Long studentId, Long sessionId, LocalDateTime deadline,
                             String clientId, String leaseToken, LocalDateTime now, boolean bestEffort) {
        try {
            Long result = redis.execute(
                ACQUIRE_SCRIPT, List.of(key(examId, studentId)),
                clientId, leaseToken, String.valueOf(sessionId), String.valueOf(examId),
                String.valueOf(studentId), String.valueOf(epochMillis(deadline)),
                String.valueOf(epochMillis(now)), String.valueOf(ttlMillis(deadline, now))
            );
            return result != null && result == 1L;
        } catch (DataAccessException exception) {
            if (!bestEffort) {
                throw exception;
            }
            log.warn("Failed to restore Redis client lease from MySQL: examId={}, studentId={}",
                examId, studentId, exception);
            return true;
        }
    }

    private ExamClientLeaseView renewMatchingDatabaseLease(Long sessionId, String clientId,
                                                            String leaseToken, LocalDateTime now) {
        int updated = jdbc.update(
            """
                update exam_session
                   set active_client_lease_until=?,active_client_last_seen=?,update_time=?
                 where id=? and status='ANSWERING' and deadline_time>?
                   and active_client_id=? and active_client_token=?
                """,
            now.plusSeconds(properties.safeLeaseTimeoutSeconds()), now, now,
            sessionId, now, clientId, leaseToken
        );
        if (updated != 1) {
            throw conflict();
        }
        return newLease(leaseToken, now, properties.safeLeaseTimeoutSeconds() * 1_000L);
    }

    private void persistLiveness(Long sessionId, String clientId, String leaseToken, LocalDateTime now) {
        int updated = jdbc.update(
            """
                update exam_session
                   set active_client_lease_until=?,active_client_last_seen=?,update_time=?
                 where id=? and status='ANSWERING' and deadline_time>?
                   and active_client_id=? and active_client_token=?
                """,
            now.plusSeconds(properties.safeLeaseTimeoutSeconds()), now, now,
            sessionId, now, clientId, leaseToken
        );
        if (updated != 1) {
            throw conflict();
        }
    }

    private DatabaseLeaseRow loadMatchingDatabaseLease(Long examId, Long studentId, String clientId,
                                                        String leaseToken, LocalDateTime now) {
        List<DatabaseLeaseRow> rows = jdbc.query(
            """
                select id,deadline_time from exam_session
                 where exam_id=? and student_id=? and status='ANSWERING' and deadline_time>?
                   and active_client_id=? and active_client_token=?
                 limit 1
                """,
            (rs, rowNum) -> new DatabaseLeaseRow(
                rs.getLong("id"), rs.getObject("deadline_time", LocalDateTime.class)
            ),
            examId, studentId, now, clientId, leaseToken
        );
        if (rows.isEmpty()) {
            throw conflict();
        }
        return rows.getFirst();
    }

    private ExamClientLeaseView newLease(String token, LocalDateTime now, long ttlMillis) {
        return ExamClientLeaseView.builder()
            .leaseToken(token)
            .leaseExpiresAt(now.plusNanos(Math.max(1L, ttlMillis) * 1_000_000L))
            .heartbeatIntervalSeconds(properties.safeHeartbeatIntervalSeconds())
            .leaseTimeoutSeconds(properties.safeLeaseTimeoutSeconds())
            .build();
    }

    private long ttlMillis(LocalDateTime deadline, LocalDateTime now) {
        long untilDeadline = Duration.between(now, deadline).toMillis();
        if (untilDeadline <= 0) {
            throw new BusinessException(REQUIRED_CODE, "考试作答时间已结束");
        }
        return Math.min(properties.safeLeaseTimeoutSeconds() * 1_000L, untilDeadline);
    }

    private void deleteRedisIfToken(Long examId, Long studentId, String leaseToken) {
        try {
            redis.execute(DELETE_SCRIPT, List.of(key(examId, studentId)), leaseToken);
        } catch (DataAccessException exception) {
            log.warn("Failed to clear Redis client lease: examId={}, studentId={}", examId, studentId, exception);
        }
    }

    private void registerRollbackCleanup(Long examId, Long studentId, String leaseToken) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteRedisIfToken(examId, studentId, leaseToken);
                }
            }
        });
    }

    private void validateClientId(String clientId) {
        if (!hasText(clientId) || clientId.length() > MAX_CLIENT_ID_LENGTH) {
            throw new BusinessException(REQUIRED_CODE, "考试客户端标识缺失，请刷新考试页面后重试");
        }
    }

    private void validateToken(String leaseToken) {
        if (!hasText(leaseToken) || leaseToken.length() > MAX_TOKEN_LENGTH) {
            throw new BusinessException(REQUIRED_CODE, "考试窗口租约已失效，请返回考试列表后重新进入");
        }
    }

    private BusinessException conflict() {
        return new BusinessException(CONFLICT_CODE, "本场考试已在其他窗口答题，请回到原窗口继续作答或等待租约过期后重试");
    }

    private String key(Long examId, Long studentId) {
        return "exam:client-lease:{" + examId + ":" + studentId + "}";
    }

    private long epochMillis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDateTime localDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private String asString(Object value) {
        return value instanceof byte[] bytes
            ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DatabaseLeaseRow(Long sessionId, LocalDateTime deadline) {
    }
}
