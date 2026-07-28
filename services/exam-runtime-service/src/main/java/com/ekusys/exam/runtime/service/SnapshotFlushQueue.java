package com.ekusys.exam.runtime.service;

import com.ekusys.exam.runtime.config.SnapshotProperties;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 答题快照异步落库队列服务（Redis 操作底层实现）。
 *
 * <h3>架构定位</h3>
 * <p>本类是考试运行时服务（exam-runtime-service）中<b>答题快照异步落库机制</b>的核心数据访问层。
 * 它通过大量的 Redis Lua 脚本保证多节点并发下的操作原子性、版本控制与租约抢占。</p>
 *
 * <h3>核心 Redis 数据结构设计</h3>
 * <ul>
 *     <li><b>{@code exam:snapshot:{examId}:{studentId}} (String)</b>: 存储最新的快照 JSON 载荷（Payload）。</li>
 *     <li><b>{@code exam:snapshot-version:{examId}:{studentId}} (String)</b>: 存储单调递增的快照版本号（snapshotVersion）。</li>
 *     <li><b>{@code exam:snapshot-flush:dirty} (ZSet)</b>: 待落库快照集合。score 为预计可落库的时间戳 (epoch ms)，member 为 {@code {examId}:{studentId}}。</li>
 *     <li><b>{@code exam:snapshot-flush:processing} (ZSet)</b>: 正在处理中的快照集合。score 为认领租约到期时间戳。</li>
 *     <li><b>{@code exam:snapshot-flush:tokens} (Hash)</b>: 记录处理租约 Token。field = member, value = token。</li>
 *     <li><b>{@code exam:snapshot-flush:attempts} (Hash)</b>: 记录累计失败重试次数。field = member, value = attempts。</li>
 *     <li><b>{@code exam:snapshot-flush:errors} (Hash)</b>: 记录最后一次失败的错误简述。field = member, value = errorMessage。</li>
 *     <li><b>{@code exam:snapshot-flush:failed} (ZSet)</b>: 隔离队列（Quarantine）。记录多次重试仍然失败的"毒药"快照。score 为隔离时间戳。</li>
 *     <li><b>{@code exam:snapshot-flush:failed-versions} (Hash)</b>: 记录被隔离快照时的版本号。field = member, value = version。</li>
 * </ul>
 *
 * <h3>核心机制</h3>
 * <ol>
 *     <li><b>版本控制与覆盖保护</b>：版本号仅递增更新；新版本写入时会自动清理旧版本的重试与错误状态。</li>
 *     <li><b>租约抢占与自愈（Lease & Recovery）</b>：当节点领走快照处理时会加上带 TTL 的租约 Token。若处理节点宕机，超过租约时间的快照会被 {@code RECOVER_SCRIPT} 自动回收到 dirty 队列重试。</li>
 *     <li><b>失败分类与隔离（Quarantine）</b>：区分轻微异常与毒药消息（Poison Message），重试超过阈值后自动隔离，避免死循环阻塞队列。</li>
 * </ol>
 *
 * @see SnapshotFlushCoordinator 快照落库协调器（业务编排层）
 * @see SnapshotFlushScheduler 定时任务调度器
 */
@Component
public class SnapshotFlushQueue {
    /** 待处理的脏快照 Sorted Set 键名 */
    static final String DIRTY_KEY = "exam:snapshot-flush:dirty";
    /** 处理中的快照 Sorted Set 键名（score 为租约到期时间戳） */
    static final String PROCESSING_KEY = "exam:snapshot-flush:processing";
    /** 处理租约 Token 哈希表键名 */
    static final String TOKENS_KEY = "exam:snapshot-flush:tokens";
    /** 重试次数哈希表键名 */
    static final String ATTEMPTS_KEY = "exam:snapshot-flush:attempts";
    /** 错误信息哈希表键名 */
    static final String ERRORS_KEY = "exam:snapshot-flush:errors";
    /** 隔离的失败快照 Sorted Set 键名 */
    static final String FAILED_KEY = "exam:snapshot-flush:failed";
    /** 隔离快照版本号哈希表键名 */
    static final String FAILED_VERSIONS_KEY = "exam:snapshot-flush:failed-versions";
    /** 分页扫描对账游标键名 */
    static final String RECONCILE_CURSOR_KEY = "exam:snapshot-flush:reconcile:cursor";
    /** 对账分布式锁键名 */
    static final String RECONCILE_LOCK_KEY = "exam:snapshot-flush:reconcile:lock";

    /** 存入 errors 哈希表时的异常信息最大截断长度 */
    private static final int MAX_ERROR_LENGTH = 1_000;

    /**
     * <b>保存快照 Lua 脚本</b>
     * <p>原子比较版本号并更新快照载荷。仅当传入版本号大于当前版本号时允许写入；
     * 写入成功后会自动清空旧的失败/重试状态，并将该成员加入 dirty 队列。</p>
     */
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

    /**
     * <b>超时租约回收 Lua 脚本</b>
     * <p>扫描 processing 队列中租约已到期的成员，将其从 processing 移除并重新推进 dirty 队列。</p>
     */
    private static final DefaultRedisScript<Long> RECOVER_SCRIPT = new DefaultRedisScript<>("""
        local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
        for _, member in ipairs(expired) do
            redis.call('ZREM', KEYS[1], member)
            redis.call('HDEL', KEYS[2], member)
            redis.call('ZADD', KEYS[3], ARGV[1], member)
        end
        return #expired
        """, Long.class);

    /**
     * <b>批量认领快照 Lua 脚本</b>
     * <p>从 dirty 队列中按 score 提取已到期的数据，将其转入 processing 队列，
     * 并生成专属的租约 Token，实现多节点竞争认领时的线程/节点安全。</p>
     */
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

    /**
     * <b>落库成功确认 Lua 脚本</b>
     * <p>验证当前持有的租约 Token 是否有效。若有效，删除租约及 processing 队列条目。
     * 若被落库的版本与最新存储版本一致，说明该条目已被完整落库，清理原始快照与所有失败标记。</p>
     */
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

    /**
     * <b>读取快照内容 Lua 脚本</b>
     * <p>读取前再次安全校验租约 Token。若租约有效则返回快照 JSON 载荷与版本号。</p>
     */
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

    /**
     * <b>缺失快照确认清理 Lua 脚本</b>
     * <p>当处理节点认领了某快照但读取时发现快照体在 Redis 中已不存在（如已过期或被意外清理），
     * 清理对应 Token 及状态，防止死锁滞留。</p>
     */
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

    /**
     * <b>处理失败标记与重试/隔离控制 Lua 脚本</b>
     * <p>当落库失败时执行：校验 Token → 递增尝试次数 → 记录错误文本 →
     * 根据失败模式（POISON 或达到最大重试次数）决定是放入隔离队列 (failed) 还是重新推入 dirty 队列延迟重试。</p>
     */
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

    /**
     * <b>条件舍弃指定版本快照 Lua 脚本</b>
     */
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

    /**
     * <b>彻底清空学生某场考试快照相关数据的 Lua 脚本</b>
     */
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

    /**
     * <b>慢扫描对账 Lua 脚本</b>
     * <p>使用 SCAN 遍历快照键，检查是否存在遗漏在队列之外的快照并自动补偿压入 dirty 队列。</p>
     */
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

    /**
     * <b>释放分布式锁 Lua 脚本</b>
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    /**
     * <b>清理长期沉淀的已隔离失败快照 Lua 脚本</b>
     */
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
    /** 本工作节点实例唯一标识，用于生成独一无二的认领租约 Token */
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

    /**
     * 保存学生提交的新快照载荷。
     *
     * @param examId    考试 ID
     * @param studentId 学生 ID
     * @param version   快照单调递增版本号
     * @param payload   答题内容 JSON 字符串
     * @param ttlMillis 快照在 Redis 中的过期生存时间 (ms)
     * @return 若保存成功返回正数版本号；若当前已有更新版本返回负数版本号
     */
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

    /**
     * 批量认领待落库的脏快照任务。
     * <p>内部会先回收超时未处理完成的租约，然后按批次认领属于本节点的快照清单。</p>
     *
     * @return 认领到的批次结果 {@link SnapshotFlushClaimBatch}（包含被认领的成员及其 Token List，以及本次回收的租约数）
     */
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

    /**
     * 根据凭据读取认领的快照内容。
     *
     * @param claim 认领凭据
     * @return 快照读取结果 {@link SnapshotFlushRead}
     */
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

    /**
     * 确认快照落库成功。
     *
     * @param claim   认领凭据
     * @param version 已成功落库的版本号
     * @return 0 表示租约失效/丢失；1 表示完全确认清理；2 表示确认成功但 Redis 中已产生更高的版本
     */
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

    /**
     * 确认并清理内容缺失的快照任务。
     *
     * @param claim 认领凭据
     * @return 是否成功清理
     */
    public boolean acknowledgeMissing(SnapshotFlushClaim claim) {
        Long result = redis.execute(
            ACK_MISSING_SCRIPT,
            List.of(PROCESSING_KEY, TOKENS_KEY, ATTEMPTS_KEY, ERRORS_KEY),
            claim.member(), claim.leaseToken()
        );
        return result != null && result == 1L;
    }

    /**
     * 标记快照落库失败并处理重试/隔离策略。
     *
     * @param claim       认领凭据
     * @param version     处理失败时的版本号
     * @param failure     异常对象
     * @param mode        失败模式（TRANSIENT / RETRYABLE / POISON）
     * @param delayMillis 退避延迟毫秒数
     * @return 失败处理结果 {@link SnapshotFailureResult}
     */
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

    /**
     * 当指定版本匹配时丢弃快照载荷。
     */
    public boolean discardPayloadIfVersion(Long examId, Long studentId, long version) {
        String member = member(examId, studentId);
        Long result = redis.execute(
            DISCARD_SCRIPT,
            List.of(snapshotKey(member), versionKey(member), DIRTY_KEY, ATTEMPTS_KEY, ERRORS_KEY),
            String.valueOf(version), member
        );
        return result != null && result == 1L;
    }

    /**
     * 清理指定学生某考试相关的全部快照与状态数据。
     */
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

    /**
     * 读取指定学生某考试的快照 JSON 原文。
     */
    public String loadPayload(Long examId, Long studentId) {
        return redis.opsForValue().get(snapshotKey(member(examId, studentId)));
    }

    /**
     * 执行一页慢扫描对账，发现并压入漏掉的脏快照。
     * <p>方法内部使用分布式锁保证同一时刻只有一个节点执行 SCAN 对账。</p>
     *
     * @return 本次扫描新增压入 dirty 队列的快照数量
     */
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

    /**
     * 清理超过保留期限的已隔离失败快照。
     *
     * @return 本次清理删除的记录数
     */
    public int cleanupFailed() {
        long cutoff = nowSupplier.getAsLong() - properties.safeFlushFailedRetentionMs();
        Long result = redis.execute(
            CLEANUP_FAILED_SCRIPT,
            List.of(FAILED_KEY, ATTEMPTS_KEY, ERRORS_KEY, FAILED_VERSIONS_KEY),
            String.valueOf(cutoff), String.valueOf(properties.safeFlushCleanupBatchSize())
        );
        return result == null ? 0 : result.intValue();
    }

    /**
     * 获取队列当前的积压统计指标和最老脏快照的逾期时长。
     */
    public SnapshotFlushBacklog backlog() {
        Set<ZSetOperations.TypedTuple<String>> oldest =
            redis.opsForZSet().rangeWithScores(DIRTY_KEY, 0, 0);
        long overdueMs = 0L;
        if (oldest != null && !oldest.isEmpty()) {
            Double score = oldest.iterator().next().getScore();
            if (score != null) {
                overdueMs = Math.max(0L, nowSupplier.getAsLong() - score.longValue());
            }
        }
        return new SnapshotFlushBacklog(
            value(redis.opsForZSet().zCard(DIRTY_KEY)),
            value(redis.opsForZSet().zCard(PROCESSING_KEY)),
            value(redis.opsForZSet().zCard(FAILED_KEY)),
            overdueMs
        );
    }

    /**
     * 获取成员下一个预期的重试次数。
     */
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
