package com.ekusys.exam.runtime.service;

import com.ekusys.exam.runtime.config.SnapshotProperties;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class SnapshotFlushQueue {
    static final String DIRTY_KEY = "exam:snapshot-flush:dirty";
    static final String PROCESSING_KEY = "exam:snapshot-flush:processing";
    static final String TOKENS_KEY = "exam:snapshot-flush:tokens";
    static final String ATTEMPTS_KEY = "exam:snapshot-flush:attempts";
    static final String ERRORS_KEY = "exam:snapshot-flush:errors";
    static final String FAILED_KEY = "exam:snapshot-flush:failed";
    static final String FAILED_VERSIONS_KEY = "exam:snapshot-flush:failed-versions";
    static final String RECONCILE_CURSOR_KEY = "exam:snapshot-flush:reconcile:cursor";
    static final String RECONCILE_LOCK_KEY = "exam:snapshot-flush:reconcile:lock";

    private static final int MAX_ERROR_LENGTH = 1_000;
    private static final DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[2])
        local incoming = tonumber(ARGV[1])
        local currentNumber = tonumber(current)
        if currentNumber and currentNumber >= incoming then
            if currentNumber == incoming and redis.call('EXISTS', KEYS[1]) == 1
                and not redis.call('ZSCORE', KEYS[4], ARGV[5]) then
                redis.call('ZADD', KEYS[3], 'NX', ARGV[4], ARGV[5])
            end
            return -currentNumber
        end
        redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
        redis.call('SET', KEYS[2], tostring(incoming), 'PX', ARGV[3])
        redis.call('ZADD', KEYS[3], 'NX', ARGV[4], ARGV[5])
        redis.call('ZREM', KEYS[5], ARGV[5])
        redis.call('HDEL', KEYS[6], ARGV[5])
        redis.call('HDEL', KEYS[7], ARGV[5])
        redis.call('HDEL', KEYS[8], ARGV[5])
        return incoming
        """, Long.class);
    private static final DefaultRedisScript<Long> RECOVER_SCRIPT = new DefaultRedisScript<>("""
        local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
        for _, member in ipairs(expired) do
            redis.call('ZREM', KEYS[1], member)
            redis.call('HDEL', KEYS[2], member)
            redis.call('ZADD', KEYS[3], ARGV[1], member)
        end
        return #expired
        """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>("""
        local batch = tonumber(ARGV[2])
        local candidates = redis.call(
            'ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, batch * 4
        )
        local result = {}
        local claimed = 0
        for _, member in ipairs(candidates) do
            if claimed >= batch then
                break
            end
            if not redis.call('ZSCORE', KEYS[2], member) then
                if redis.call('ZREM', KEYS[1], member) == 1 then
                    claimed = claimed + 1
                    local token = ARGV[4] .. ':' .. ARGV[1] .. ':' .. tostring(claimed)
                    redis.call('ZADD', KEYS[2], tonumber(ARGV[1]) + tonumber(ARGV[3]), member)
                    redis.call('HSET', KEYS[3], member, token)
                    table.insert(result, member)
                    table.insert(result, token)
                end
            end
        end
        return result
        """, List.class);
    private static final DefaultRedisScript<Long> ACK_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[4], ARGV[1]) ~= ARGV[2] then
            return 0
        end
        redis.call('HDEL', KEYS[4], ARGV[1])
        redis.call('ZREM', KEYS[3], ARGV[1])
        local current = redis.call('GET', KEYS[2])
        if current and tostring(current) == tostring(ARGV[3]) then
            redis.call('DEL', KEYS[1])
            redis.call('HDEL', KEYS[5], ARGV[1])
            redis.call('HDEL', KEYS[6], ARGV[1])
            redis.call('ZREM', KEYS[7], ARGV[1])
            redis.call('HDEL', KEYS[8], ARGV[1])
            return 1
        end
        return 2
        """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> LOAD_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[3], ARGV[1]) ~= ARGV[2] then
            return {'0'}
        end
        local payload = redis.call('GET', KEYS[1])
        local version = redis.call('GET', KEYS[2])
        if not payload or not version then
            return {'1'}
        end
        return {'2', tostring(version), payload}
        """, List.class);
    private static final DefaultRedisScript<Long> ACK_MISSING_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then
            return 0
        end
        redis.call('HDEL', KEYS[2], ARGV[1])
        redis.call('ZREM', KEYS[1], ARGV[1])
        redis.call('HDEL', KEYS[3], ARGV[1])
        redis.call('HDEL', KEYS[4], ARGV[1])
        return 1
        """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> FAILURE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[5], ARGV[1]) ~= ARGV[2] then
            return {'0', '0'}
        end
        redis.call('HDEL', KEYS[5], ARGV[1])
        redis.call('ZREM', KEYS[4], ARGV[1])
        local current = redis.call('GET', KEYS[2])
        if not current or tostring(current) ~= tostring(ARGV[3]) then
            return {'1', '0'}
        end
        local attempts = redis.call('HINCRBY', KEYS[6], ARGV[1], 1)
        redis.call('HSET', KEYS[7], ARGV[1], ARGV[5])
        local retention = tonumber(ARGV[9])
        local function extendTtl(key)
            if redis.call('EXISTS', key) == 1 then
                local ttl = redis.call('PTTL', key)
                if ttl < retention then
                    redis.call('PEXPIRE', key, retention)
                end
            end
        end
        extendTtl(KEYS[1])
        extendTtl(KEYS[2])
        local quarantine = ARGV[7] == 'POISON'
            or (ARGV[7] == 'RETRYABLE' and attempts >= tonumber(ARGV[6]))
        if quarantine then
            redis.call('ZREM', KEYS[3], ARGV[1])
            redis.call('ZADD', KEYS[8], ARGV[8], ARGV[1])
            redis.call('HSET', KEYS[9], ARGV[1], ARGV[3])
            return {'3', tostring(attempts)}
        end
        redis.call('ZADD', KEYS[3], ARGV[4], ARGV[1])
        return {'2', tostring(attempts)}
        """, List.class);
    private static final DefaultRedisScript<Long> DISCARD_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[2])
        if current and tostring(current) == tostring(ARGV[1]) then
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[3], ARGV[2])
            redis.call('HDEL', KEYS[4], ARGV[2])
            redis.call('HDEL', KEYS[5], ARGV[2])
            return 1
        end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> CLEAR_SCRIPT = new DefaultRedisScript<>("""
        redis.call('DEL', KEYS[1], KEYS[2])
        redis.call('ZREM', KEYS[3], ARGV[1])
        redis.call('ZREM', KEYS[4], ARGV[1])
        redis.call('HDEL', KEYS[5], ARGV[1])
        redis.call('HDEL', KEYS[6], ARGV[1])
        redis.call('HDEL', KEYS[7], ARGV[1])
        redis.call('ZREM', KEYS[8], ARGV[1])
        redis.call('HDEL', KEYS[9], ARGV[1])
        return 1
        """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> RECONCILE_SCRIPT = new DefaultRedisScript<>("""
        local cursor = redis.call('GET', KEYS[3]) or '0'
        local scan = redis.call('SCAN', cursor, 'MATCH', 'exam:snapshot:*', 'COUNT', ARGV[2])
        local prefix = 'exam:snapshot:'
        local added = 0
        for _, key in ipairs(scan[2]) do
            local member = string.sub(key, string.len(prefix) + 1)
            if string.match(member, '^%d+:%d+$')
                and not redis.call('ZSCORE', KEYS[2], member) then
                added = added + redis.call('ZADD', KEYS[1], 'NX', ARGV[1], member)
            end
        end
        redis.call('SET', KEYS[3], scan[1])
        return {tostring(scan[1]), tostring(added)}
        """, List.class);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> CLEANUP_FAILED_SCRIPT = new DefaultRedisScript<>("""
        local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
        for _, member in ipairs(expired) do
            local failedVersion = redis.call('HGET', KEYS[4], member)
            local snapshotKey = 'exam:snapshot:' .. member
            local versionKey = 'exam:snapshot-version:' .. member
            local current = redis.call('GET', versionKey)
            if failedVersion and current and tostring(failedVersion) == tostring(current) then
                redis.call('DEL', snapshotKey, versionKey)
            end
            redis.call('ZREM', KEYS[1], member)
            redis.call('HDEL', KEYS[2], member)
            redis.call('HDEL', KEYS[3], member)
            redis.call('HDEL', KEYS[4], member)
        end
        return #expired
        """, Long.class);

    private final StringRedisTemplate redis;
    private final SnapshotProperties properties;
    private final LongSupplier nowSupplier;
    private final String workerId = UUID.randomUUID().toString();

    @Autowired
    public SnapshotFlushQueue(StringRedisTemplate redis, SnapshotProperties properties) {
        this(redis, properties, System::currentTimeMillis);
    }

    SnapshotFlushQueue(StringRedisTemplate redis, SnapshotProperties properties, LongSupplier nowSupplier) {
        this.redis = redis;
        this.properties = properties;
        this.nowSupplier = nowSupplier;
    }

    public Long save(Long examId, Long studentId, long version, String payload, long ttlMillis) {
        String member = member(examId, studentId);
        long now = nowSupplier.getAsLong();
        return redis.execute(
            SAVE_SCRIPT,
            List.of(
                snapshotKey(member), versionKey(member), DIRTY_KEY, PROCESSING_KEY,
                FAILED_KEY, ATTEMPTS_KEY, ERRORS_KEY, FAILED_VERSIONS_KEY
            ),
            String.valueOf(version), payload, String.valueOf(ttlMillis),
            String.valueOf(now + properties.safeFlushIntervalMs()), member
        );
    }

    public SnapshotFlushClaimBatch claimBatch() {
        long now = nowSupplier.getAsLong();
        int batchSize = properties.safeFlushBatchSize();
        Long recovered = redis.execute(
            RECOVER_SCRIPT,
            List.of(PROCESSING_KEY, TOKENS_KEY, DIRTY_KEY),
            String.valueOf(now), String.valueOf(batchSize)
        );
        List<?> claimed = redis.execute(
            CLAIM_SCRIPT,
            List.of(DIRTY_KEY, PROCESSING_KEY, TOKENS_KEY),
            String.valueOf(now), String.valueOf(batchSize),
            String.valueOf(properties.safeFlushLeaseMs()), workerId
        );
        List<SnapshotFlushClaim> claims = new ArrayList<>();
        if (claimed != null) {
            for (int index = 0; index + 1 < claimed.size(); index += 2) {
                claims.add(new SnapshotFlushClaim(
                    asString(claimed.get(index)), asString(claimed.get(index + 1))
                ));
            }
        }
        return new SnapshotFlushClaimBatch(List.copyOf(claims), recovered == null ? 0 : recovered.intValue());
    }

    public SnapshotFlushRead read(SnapshotFlushClaim claim) {
        List<?> result = redis.execute(
            LOAD_SCRIPT,
            List.of(snapshotKey(claim.member()), versionKey(claim.member()), TOKENS_KEY),
            claim.member(), claim.leaseToken()
        );
        if (result == null || result.isEmpty() || "0".equals(asString(result.getFirst()))) {
            return SnapshotFlushRead.leaseLost();
        }
        if ("1".equals(asString(result.getFirst())) || result.size() < 3) {
            return SnapshotFlushRead.missing();
        }
        return new SnapshotFlushRead(
            true, asString(result.get(2)), Long.valueOf(asString(result.get(1)))
        );
    }

    public long acknowledge(SnapshotFlushClaim claim, long version) {
        Long result = redis.execute(
            ACK_SCRIPT,
            List.of(
                snapshotKey(claim.member()), versionKey(claim.member()), PROCESSING_KEY,
                TOKENS_KEY, ATTEMPTS_KEY, ERRORS_KEY, FAILED_KEY, FAILED_VERSIONS_KEY
            ),
            claim.member(), claim.leaseToken(), String.valueOf(version)
        );
        return result == null ? 0L : result;
    }

    public boolean acknowledgeMissing(SnapshotFlushClaim claim) {
        Long result = redis.execute(
            ACK_MISSING_SCRIPT,
            List.of(PROCESSING_KEY, TOKENS_KEY, ATTEMPTS_KEY, ERRORS_KEY),
            claim.member(), claim.leaseToken()
        );
        return result != null && result == 1L;
    }

    public SnapshotFailureResult markFailure(SnapshotFlushClaim claim, long version,
                                             Throwable failure, SnapshotFailureMode mode,
                                             long delayMillis) {
        long now = nowSupplier.getAsLong();
        List<?> result = redis.execute(
            FAILURE_SCRIPT,
            List.of(
                snapshotKey(claim.member()), versionKey(claim.member()), DIRTY_KEY,
                PROCESSING_KEY, TOKENS_KEY, ATTEMPTS_KEY, ERRORS_KEY, FAILED_KEY,
                FAILED_VERSIONS_KEY
            ),
            claim.member(), claim.leaseToken(), String.valueOf(version),
            String.valueOf(now + delayMillis), errorMessage(failure),
            String.valueOf(properties.safeFlushMaxAttempts()), mode.name(),
            String.valueOf(now), String.valueOf(properties.safeFlushFailedRetentionMs())
        );
        if (result == null || result.size() < 2) {
            return SnapshotFailureResult.leaseLost();
        }
        int code = Integer.parseInt(asString(result.get(0)));
        int attempts = Integer.parseInt(asString(result.get(1)));
        return switch (code) {
            case 1 -> SnapshotFailureResult.staleResult();
            case 2 -> new SnapshotFailureResult(true, false, false, attempts);
            case 3 -> new SnapshotFailureResult(true, false, true, attempts);
            default -> SnapshotFailureResult.leaseLost();
        };
    }

    public boolean discardPayloadIfVersion(Long examId, Long studentId, long version) {
        String member = member(examId, studentId);
        Long result = redis.execute(
            DISCARD_SCRIPT,
            List.of(snapshotKey(member), versionKey(member), DIRTY_KEY, ATTEMPTS_KEY, ERRORS_KEY),
            String.valueOf(version), member
        );
        return result != null && result == 1L;
    }

    public void clear(Long examId, Long studentId) {
        String member = member(examId, studentId);
        redis.execute(
            CLEAR_SCRIPT,
            List.of(
                snapshotKey(member), versionKey(member), DIRTY_KEY, PROCESSING_KEY,
                TOKENS_KEY, ATTEMPTS_KEY, ERRORS_KEY, FAILED_KEY, FAILED_VERSIONS_KEY
            ),
            member
        );
    }

    public String loadPayload(Long examId, Long studentId) {
        return redis.opsForValue().get(snapshotKey(member(examId, studentId)));
    }

    public int reconcilePage() {
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(
            RECONCILE_LOCK_KEY, lockToken, Duration.ofSeconds(30)
        );
        if (!Boolean.TRUE.equals(acquired)) {
            return 0;
        }
        try {
            List<?> result = redis.execute(
                RECONCILE_SCRIPT,
                List.of(DIRTY_KEY, PROCESSING_KEY, RECONCILE_CURSOR_KEY),
                String.valueOf(nowSupplier.getAsLong()),
                String.valueOf(properties.safeFlushReconcileScanCount())
            );
            return result == null || result.size() < 2
                ? 0
                : Integer.parseInt(asString(result.get(1)));
        } finally {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(RECONCILE_LOCK_KEY), lockToken);
        }
    }

    public int cleanupFailed() {
        long cutoff = nowSupplier.getAsLong() - properties.safeFlushFailedRetentionMs();
        Long result = redis.execute(
            CLEANUP_FAILED_SCRIPT,
            List.of(FAILED_KEY, ATTEMPTS_KEY, ERRORS_KEY, FAILED_VERSIONS_KEY),
            String.valueOf(cutoff), String.valueOf(properties.safeFlushCleanupBatchSize())
        );
        return result == null ? 0 : result.intValue();
    }

    public SnapshotFlushBacklog backlog() {
        return new SnapshotFlushBacklog(
            value(redis.opsForZSet().zCard(DIRTY_KEY)),
            value(redis.opsForZSet().zCard(PROCESSING_KEY)),
            value(redis.opsForZSet().zCard(FAILED_KEY))
        );
    }

    public int nextAttempt(String member) {
        Object attempts = redis.opsForHash().get(ATTEMPTS_KEY, member);
        if (attempts == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(asString(attempts)) + 1);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    static String member(Long examId, Long studentId) {
        return examId + ":" + studentId;
    }

    static String snapshotKey(String member) {
        return "exam:snapshot:" + member;
    }

    static String versionKey(String member) {
        return "exam:snapshot-version:" + member;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private String errorMessage(Throwable failure) {
        String message = failure == null ? "unknown snapshot flush failure" : failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure == null ? "unknown snapshot flush failure" : failure.getClass().getSimpleName();
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private String asString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }
}
