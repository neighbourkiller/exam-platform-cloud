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

/**
 * 考试客户端租约服务。
 *
 * <h3>设计目标</h3>
 * <p>保证同一学生在同一场考试中，<b>有且仅有一个浏览器窗口</b>可以正常作答（"单活约束"）。
 * 服务端通过"租约（Lease）"机制实现：客户端定期发送心跳来续租，若超时未续则租约释放，
 * 其他窗口方可重新获取。</p>
 *
 * <h3>双层存储架构</h3>
 * <ul>
 *     <li><b>Redis（快路径）</b> — 承载高频心跳续租的读写，利用 Lua 脚本保证原子性，
 *         并通过 Hash TTL 实现租约自动过期。</li>
 *     <li><b>MySQL（兜底 + 持久化）</b> — 存放权威租约状态（exam_session 表），
 *         用于 Redis 不可用时的降级，以及定期将活跃信息持久化。</li>
 * </ul>
 *
 * <h3>关键概念</h3>
 * <ul>
 *     <li><b>clientId</b> — 浏览器生成的唯一标识，用于区分不同窗口/设备。</li>
 *     <li><b>leaseToken</b> — 服务端颁发的 UUID 令牌，证明持有者是当前合法的租约持有人。</li>
 *     <li><b>deadline</b> — 考试会话的截止时间（与租约超时不同，这是考试层面的终止时刻）。</li>
 *     <li><b>TTL</b> — 租约生存时间，取 leaseTimeout 和距 deadline 剩余时间的较小值。</li>
 * </ul>
 *
 * @see ClientLeaseProperties 可配置的心跳间隔、租约超时、持久化间隔
 * @see ExamClientLeaseContext 续租操作的内部返回值容器
 */
@Service
public class ExamClientLeaseService {

    // ==================== 业务异常码 ====================

    /** 客户端标识或令牌缺失/失效，需要客户端重新进入考试 */
    public static final String REQUIRED_CODE = "EXAM_CLIENT_REQUIRED";

    /** 租约冲突：另一个窗口已经持有该考试的活跃租约 */
    public static final String CONFLICT_CODE = "EXAM_CLIENT_CONFLICT";

    /** Redis 不可用且无法安全降级时抛出 */
    public static final String UNAVAILABLE_CODE = "EXAM_CLIENT_LEASE_UNAVAILABLE";

    // ==================== 输入校验常量 ====================

    /** clientId 最大允许长度，防止恶意超长输入 */
    private static final int MAX_CLIENT_ID_LENGTH = 128;

    /** leaseToken 最大允许长度 */
    private static final int MAX_TOKEN_LENGTH = 128;

    private static final Logger log = LoggerFactory.getLogger(ExamClientLeaseService.class);

    // ==================== Redis Lua 脚本 ====================

    /**
     * <b>续租脚本（RENEW_SCRIPT）</b>
     * <p>原子地执行以下逻辑：</p>
     * <ol>
     *     <li>键不存在 → 返回 {@code "0"}（租约未在 Redis 中，需走 MySQL 降级）。</li>
     *     <li>clientId 或 token 不匹配 → 返回 {@code "-1"}（冲突）。</li>
     *     <li>deadline 已过 → 删除键，返回 {@code "-2"}（考试已结束）。</li>
     *     <li>校验通过 → 更新 lastSeenEpochMs（及可选的 lastSnapshotEpochMs），
     *         判断是否需要持久化到 MySQL（间隔超过 persistenceInterval），
     *         刷新 Hash 的 TTL，返回 {@code "1"} + sessionId + deadline + ttl + persist 标记。</li>
     * </ol>
     *
     * <p>KEYS[1] = exam:client-lease:{examId:studentId}</p>
     * <p>ARGV: [1]clientId [2]token [3]nowEpochMs [4]leaseTimeoutMs [5]isSnapshot("0"/"1")
     *         [6]persistenceIntervalMs</p>
     */
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

    /**
     * <b>获取/激活脚本（ACQUIRE_SCRIPT）</b>
     * <p>原子地执行以下逻辑：</p>
     * <ol>
     *     <li>键已存在且 clientId + token 匹配 → 刷新 TTL，返回 1（幂等重入）。</li>
     *     <li>键已存在但不匹配 → 返回 0（冲突，另一个客户端持有租约）。</li>
     *     <li>键不存在 → 创建 Hash 并设置全部字段和 TTL，返回 1（首次获取成功）。</li>
     * </ol>
     *
     * <p>KEYS[1] = exam:client-lease:{examId:studentId}</p>
     * <p>ARGV: [1]clientId [2]token [3]sessionId [4]examId [5]studentId
     *         [6]deadlineEpochMs [7]nowEpochMs [8]ttlMs</p>
     */
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

    /**
     * <b>条件删除脚本（DELETE_SCRIPT）</b>
     * <p>仅当 Redis Hash 中存储的 token 与传入的 token 匹配时才删除键，
     * 防止误删其他客户端持有的租约。</p>
     *
     * <p>KEYS[1] = exam:client-lease:{examId:studentId}</p>
     * <p>ARGV[1] = leaseToken</p>
     */
    private static final DefaultRedisScript<Long> DELETE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('HGET', KEYS[1], 'token') == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    // ==================== 依赖 ====================

    /** 用于 MySQL 兜底查询和持久化 */
    private final JdbcTemplate jdbc;

    /** 用于 Redis 快路径操作 */
    private final StringRedisTemplate redis;

    /** 租约相关的可配置参数（心跳间隔、超时、持久化间隔） */
    private final ClientLeaseProperties properties;

    public ExamClientLeaseService(JdbcTemplate jdbc, StringRedisTemplate redis,
                                  ClientLeaseProperties properties) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.properties = properties;
    }

    // ==================== 公开方法 ====================

    /**
     * 创建初始租约（尚未激活到 Redis）。
     * <p>通常在开考（start exam）流程中调用，生成 token 和租约视图，
     * 随后由 {@link #activateAfterCommit} 在事务提交后写入 Redis。</p>
     *
     * @param clientId 浏览器端生成的客户端唯一标识
     * @param now      当前时间
     * @return 包含 leaseToken、过期时间和心跳参数的租约视图
     */
    public ExamClientLeaseView createInitialLease(String clientId, LocalDateTime now) {
        validateClientId(clientId);
        return newLease(UUID.randomUUID().toString(), now, properties.safeLeaseTimeoutSeconds() * 1_000L);
    }

    /**
     * 在当前事务提交后将租约激活到 Redis。
     * <p>为什么要延迟到 afterCommit？因为 Redis 写入不参与数据库事务——如果先写 Redis
     * 再提交 MySQL，MySQL 回滚后 Redis 中会留下"幽灵租约"。延迟到 afterCommit
     * 可以保证只有 MySQL 成功落库后才写 Redis。</p>
     *
     * <p>如果没有活跃的事务同步（例如单元测试或非事务上下文），则立即执行激活。</p>
     *
     * @param examId    考试 ID
     * @param studentId 学生 ID
     * @param sessionId 考试会话 ID
     * @param deadline  考试截止时间
     * @param clientId  客户端标识
     * @param lease     之前由 {@link #createInitialLease} 创建的租约视图
     * @param now       当前时间
     */
    public void activateAfterCommit(Long examId, Long studentId, Long sessionId, LocalDateTime deadline,
                                    String clientId, ExamClientLeaseView lease, LocalDateTime now) {
        Runnable activation = () -> activate(
            examId, studentId, sessionId, deadline, clientId, lease.getLeaseToken(), now, true
        );
        // 如果没有活跃的事务同步，直接执行
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            activation.run();
            return;
        }
        // 注册事务同步回调：仅在事务成功提交后执行 Redis 激活
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activation.run();
            }
        });
    }

    /**
     * 在 MySQL 激活事务已提交后严格写入 Redis 租约。
     * Redis 不可用时保留数据库会话并返回可重试错误，调用方可使用同一数据库令牌重试。
     */
    public ExamClientLeaseView activateCommitted(Long examId, Long studentId, Long sessionId,
                                                 LocalDateTime deadline, String clientId,
                                                 String leaseToken, LocalDateTime now) {
        validateClientId(clientId);
        validateToken(leaseToken);
        long ttlMillis = ttlMillis(deadline, now);
        try {
            if (!activate(examId, studentId, sessionId, deadline, clientId, leaseToken, now, false)) {
                throw conflict();
            }
        } catch (DataAccessException exception) {
            log.warn("Redis client lease activation failed after database commit: examId={}, studentId={}",
                examId, studentId, exception);
            throw new BusinessException(UNAVAILABLE_CODE, "考试窗口校验服务暂时不可用，请稍后重试");
        }
        return newLease(leaseToken, now, ttlMillis);
    }

    /**
     * 获取（或恢复）客户端租约。
     * <p>此方法处理两种场景：</p>
     *
     * <h4>场景一：恢复已有租约（leaseToken 非空）</h4>
     * <ol>
     *     <li>优先尝试通过 Redis 续租（{@link #renewRedis}）。</li>
     *     <li>若 Redis 不可用或键不存在，降级到 MySQL 匹配验证并刷新。</li>
     *     <li>验证通过后重新激活 Redis 缓存。</li>
     * </ol>
     *
     * <h4>场景二：新客户端接管（leaseToken 为空）</h4>
     * <ol>
     *     <li>生成新 token，先在 Redis 中 CAS 式获取租约。</li>
     *     <li>成功后更新 MySQL exam_session 表的客户端字段（WHERE 条件确保旧租约已过期
     *         或不存在，实现乐观锁）。</li>
     *     <li>MySQL 更新失败则回滚 Redis 并抛出冲突异常。</li>
     *     <li>注册事务回滚清理，确保 MySQL 回滚时 Redis 也被清理。</li>
     * </ol>
     *
     * @param examId    考试 ID
     * @param studentId 学生 ID
     * @param sessionId 考试会话 ID
     * @param deadline  考试截止时间
     * @param clientId  客户端标识
     * @param leaseToken 已有的租约令牌（为空则尝试新客户端接管）
     * @param now       当前时间
     * @return 租约视图（包含 token、过期时间、心跳间隔等）
     * @throws BusinessException CONFLICT_CODE — 已有其他活跃客户端
     * @throws BusinessException UNAVAILABLE_CODE — Redis 不可用且不能安全降级
     */
    public ExamClientLeaseView acquire(Long examId, Long studentId, Long sessionId, LocalDateTime deadline,
                                       String clientId, String leaseToken, LocalDateTime now) {
        validateClientId(clientId);

        // ---------- 场景一：客户端携带已有 token，尝试恢复租约 ----------
        if (hasText(leaseToken)) {
            validateToken(leaseToken);
            try {
                // 快路径：Redis 续租
                ExamClientLeaseContext context = renewRedis(
                    examId, studentId, clientId, leaseToken, now, false
                );
                if (context != null) {
                    return context.lease();
                }
            } catch (DataAccessException exception) {
                // Redis 不可用，降级到 MySQL
                log.warn("Redis client lease unavailable while resuming; using MySQL: examId={}, studentId={}",
                    examId, studentId, exception);
            }
            // 降级：从 MySQL 验证并刷新租约，再尝试恢复 Redis 缓存
            ExamClientLeaseView fallback = renewMatchingDatabaseLease(sessionId, clientId, leaseToken, now);
            if (!activate(examId, studentId, sessionId, deadline, clientId, leaseToken, now, true)) {
                throw conflict();
            }
            return fallback;
        }

        // ---------- 场景二：新客户端接管 ----------
        String token = UUID.randomUUID().toString();
        long ttlMillis = ttlMillis(deadline, now);

        // 第一步：在 Redis 中原子获取租约（如果已有其他客户端的活跃租约，会返回 0）
        try {
            if (!activate(examId, studentId, sessionId, deadline, clientId, token, now, false)) {
                throw conflict();
            }
        } catch (DataAccessException exception) {
            // 新客户端接管不能在 Redis 不可用时降级，因为无法确认是否有另一个活跃窗口
            log.warn("Redis client lease unavailable; refusing a new client takeover: examId={}, studentId={}",
                examId, studentId, exception);
            throw new BusinessException(UNAVAILABLE_CODE, "考试窗口校验服务暂时不可用，请稍后重试");
        }

        // 注册事务回滚清理：如果后续 MySQL 事务回滚，清除已写入 Redis 的租约
        registerRollbackCleanup(examId, studentId, token);

        // 第二步：更新 MySQL exam_session 表，用乐观锁保证接管安全
        // WHERE 条件要求旧租约为空或已过期，防止并发窗口同时接管
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
            // MySQL 操作异常，回滚 Redis
            deleteRedisIfToken(examId, studentId, token);
            throw exception;
        }
        if (updated != 1) {
            // 没有更新到行 → 旧租约仍然活跃，接管失败，清理 Redis
            deleteRedisIfToken(examId, studentId, token);
            throw conflict();
        }
        return newLease(token, now, ttlMillis);
    }

    /**
     * 续租：客户端心跳或答题快照提交时调用。
     *
     * <p>优先走 Redis 快路径续租（{@link #renewRedis}）。如果 Redis 不可用或键丢失，
     * 降级到 MySQL 验证身份，刷新租约，并尝试恢复 Redis 缓存。</p>
     *
     * <p>当 Lua 脚本判断距上次持久化已超过 {@code persistenceInterval} 时，
     * 还会同步将活跃信息写回 MySQL（{@link #persistLiveness}），确保 MySQL
     * 中的租约状态不会长时间过时。</p>
     *
     * @param examId    考试 ID
     * @param studentId 学生 ID
     * @param clientId  客户端标识
     * @param leaseToken 租约令牌
     * @param now       当前时间
     * @param snapshot  是否为答题快照提交（true 时更新 lastSnapshotEpochMs）
     * @return 续租结果上下文（包含 sessionId、deadline、lease 等）
     * @throws BusinessException CONFLICT_CODE — 令牌不匹配
     * @throws BusinessException REQUIRED_CODE — 考试已结束
     */
    public ExamClientLeaseContext renew(Long examId, Long studentId, String clientId,
                                        String leaseToken, LocalDateTime now, boolean snapshot) {
        validateClientId(clientId);
        validateToken(leaseToken);

        // 快路径：Redis 原子续租
        try {
            ExamClientLeaseContext context = renewRedis(
                examId, studentId, clientId, leaseToken, now, snapshot
            );
            if (context != null) {
                return context;
            }
        } catch (DataAccessException exception) {
            // Redis 不可用，记录警告后降级到 MySQL
            log.warn("Redis client lease unavailable; using MySQL fallback: examId={}, studentId={}",
                examId, studentId, exception);
        }

        // 降级路径：从 MySQL 加载并验证租约
        DatabaseLeaseRow row = loadMatchingDatabaseLease(examId, studentId, clientId, leaseToken, now);
        ExamClientLeaseView lease = renewMatchingDatabaseLease(row.sessionId(), clientId, leaseToken, now);

        // 尝试将 MySQL 中的租约恢复到 Redis（bestEffort 模式，失败不阻塞）
        if (!activate(examId, studentId, row.sessionId(), row.deadline(), clientId, leaseToken, now, true)) {
            throw conflict();
        }
        return new ExamClientLeaseContext(row.sessionId(), row.deadline(), now, lease);
    }

    /**
     * 严格校验当前客户端持有有效租约。
     * <p>直接通过 MySQL 更新来验证，适用于关键操作（如交卷）前的最终确认。
     * 与 {@link #renew} 不同，这里不走 Redis，而是直接以 MySQL 为权威源，
     * 确保交卷等不可逆操作的安全性。</p>
     *
     * @param sessionId  考试会话 ID
     * @param clientId   客户端标识
     * @param leaseToken 租约令牌
     * @param now        当前时间
     * @throws BusinessException CONFLICT_CODE — 验证失败（令牌不匹配、会话非 ANSWERING 或已过截止时间）
     */
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

    /**
     * 在事务提交后清理 Redis 中的租约。
     * <p>用于交卷、强制结束等场景：MySQL 事务成功后删除 Redis 缓存，
     * 释放单活约束，允许后续重新进入（如有需要）。</p>
     *
     * <p>如果没有活跃的事务同步，则立即执行清理。</p>
     *
     * @param examId     考试 ID
     * @param studentId  学生 ID
     * @param leaseToken 租约令牌（用于条件删除，防止误删新租约）
     */
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

    /**
     * 读取 Redis 中记录的最后一次答题快照时间。
     * <p>用于判断客户端最近一次成功提交快照的时刻，辅助监考和异常检测。
     * 如果 Redis 不可用或数据不存在，安全地返回 null。</p>
     *
     * @param examId    考试 ID
     * @param studentId 学生 ID
     * @return 最后一次快照时间，如果不可用则返回 null
     */
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

    // ==================== 私有方法 ====================

    /**
     * 通过 Redis Lua 脚本原子续租。
     *
     * <p>调用 {@link #RENEW_SCRIPT}，根据返回码判断：</p>
     * <ul>
     *     <li>{@code "0"} — 键不存在（Redis 中没有该租约），返回 null 触发 MySQL 降级。</li>
     *     <li>{@code "-1"} — clientId 或 token 不匹配，抛出冲突异常。</li>
     *     <li>{@code "-2"} — deadline 已过（考试已结束），键已被删除，抛出业务异常。</li>
     *     <li>{@code "1"} — 续租成功。解析 sessionId、deadline、ttl 和 persist 标记。
     *         如果 persist=1，还需要将活跃信息持久化到 MySQL。</li>
     * </ul>
     *
     * @param examId    考试 ID
     * @param studentId 学生 ID
     * @param clientId  客户端标识
     * @param leaseToken 租约令牌
     * @param now       当前时间
     * @param snapshot  是否为快照提交
     * @return 续租上下文，如果 Redis 中无此租约则返回 null
     */
    private ExamClientLeaseContext renewRedis(Long examId, Long studentId, String clientId,
                                              String leaseToken, LocalDateTime now, boolean snapshot) {
        List<?> result = redis.execute(
            RENEW_SCRIPT, List.of(key(examId, studentId)),
            clientId, leaseToken, String.valueOf(epochMillis(now)),
            String.valueOf(properties.safeLeaseTimeoutSeconds() * 1_000L), snapshot ? "1" : "0",
            String.valueOf(properties.safePersistenceIntervalSeconds() * 1_000L)
        );

        // 键不存在 → 返回 null，让调用方降级到 MySQL
        if (result == null || result.isEmpty() || "0".equals(asString(result.getFirst()))) {
            return null;
        }

        String code = asString(result.getFirst());

        // clientId 或 token 不匹配 → 冲突
        if ("-1".equals(code)) {
            throw conflict();
        }

        // deadline 已过 → 考试已结束
        if ("-2".equals(code)) {
            throw new BusinessException(REQUIRED_CODE, "考试作答时间已结束");
        }

        // 异常返回格式 → 服务不可用
        if (!"1".equals(code) || result.size() < 5) {
            throw new BusinessException(UNAVAILABLE_CODE, "考试窗口校验服务返回异常，请稍后重试");
        }

        // 解析续租成功的返回值
        long deadlineMillis = Long.parseLong(asString(result.get(2)));
        long ttlMillis = Long.parseLong(asString(result.get(3)));
        Long sessionId = Long.valueOf(asString(result.get(1)));

        // 如果 Lua 脚本判断需要持久化（距上次持久化已超过 persistenceInterval），
        // 同步更新 MySQL 中的活跃信息
        if ("1".equals(asString(result.get(4)))) {
            persistLiveness(sessionId, clientId, leaseToken, now);
        }

        return new ExamClientLeaseContext(
            sessionId, localDateTime(deadlineMillis), now,
            newLease(leaseToken, now, ttlMillis)
        );
    }

    /**
     * 在 Redis 中激活（创建或幂等重入）租约。
     *
     * <p>调用 {@link #ACQUIRE_SCRIPT}，如果键已存在且 clientId + token 匹配则幂等刷新 TTL；
     * 如果键不存在则创建新 Hash；如果不匹配则返回 false。</p>
     *
     * @param examId     考试 ID
     * @param studentId  学生 ID
     * @param sessionId  考试会话 ID
     * @param deadline   考试截止时间
     * @param clientId   客户端标识
     * @param leaseToken 租约令牌
     * @param now        当前时间
     * @param bestEffort 是否为尽力模式：true 时 Redis 异常不抛出而返回 true（用于从 MySQL 恢复场景）；
     *                   false 时 Redis 异常直接抛出（用于新接管场景，必须确认 Redis 无冲突）
     * @return true=激活成功 false=冲突
     */
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
                // 非尽力模式：必须让调用方感知 Redis 失败
                throw exception;
            }
            // 尽力模式：Redis 失败时乐观地认为成功（MySQL 已是权威源）
            log.warn("Failed to restore Redis client lease from MySQL: examId={}, studentId={}",
                examId, studentId, exception);
            return true;
        }
    }

    /**
     * 在 MySQL 中验证并刷新匹配的租约。
     * <p>通过 UPDATE 的 WHERE 条件同时完成身份验证和续租：
     * 只有 session 处于 ANSWERING 状态、未过截止时间、且 clientId + token 完全匹配时才会更新成功。</p>
     *
     * @param sessionId  考试会话 ID
     * @param clientId   客户端标识
     * @param leaseToken 租约令牌
     * @param now        当前时间
     * @return 刷新后的租约视图
     * @throws BusinessException CONFLICT_CODE — 验证失败
     */
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

    /**
     * 将客户端活跃信息持久化到 MySQL。
     * <p>由 Redis 续租脚本中的 persist 标记触发，用于定期（由 persistenceInterval 控制）
     * 将 Redis 中的活跃状态同步到 MySQL，防止 MySQL 中的租约信息长时间过时，
     * 确保 Redis 丢失数据后 MySQL 仍可作为降级恢复源。</p>
     *
     * @param sessionId  考试会话 ID
     * @param clientId   客户端标识
     * @param leaseToken 租约令牌
     * @param now        当前时间
     * @throws BusinessException CONFLICT_CODE — 持久化时发现 MySQL 中的租约已不匹配
     */
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

    /**
     * 从 MySQL 加载匹配的租约行。
     * <p>用于 Redis 降级场景：根据 examId + studentId + clientId + token 查询 exam_session，
     * 确认该学生确实有一个活跃的、属于该客户端的考试会话。</p>
     *
     * @param examId     考试 ID
     * @param studentId  学生 ID
     * @param clientId   客户端标识
     * @param leaseToken 租约令牌
     * @param now        当前时间
     * @return 会话 ID 和截止时间
     * @throws BusinessException CONFLICT_CODE — 找不到匹配的活跃会话
     */
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

    // ==================== 工具方法 ====================

    /**
     * 构建租约视图 DTO。
     *
     * @param token    租约令牌
     * @param now      当前时间（作为计算过期时间的基准）
     * @param ttlMillis 租约生存时间（毫秒），至少 1ms
     * @return 面向客户端的租约视图
     */
    private ExamClientLeaseView newLease(String token, LocalDateTime now, long ttlMillis) {
        return ExamClientLeaseView.builder()
            .leaseToken(token)
            .leaseExpiresAt(now.plusNanos(Math.max(1L, ttlMillis) * 1_000_000L))
            .heartbeatIntervalSeconds(properties.safeHeartbeatIntervalSeconds())
            .leaseTimeoutSeconds(properties.safeLeaseTimeoutSeconds())
            .build();
    }

    /**
     * 计算租约 TTL（毫秒）。
     * <p>取配置的租约超时和距考试截止时间剩余毫秒数的较小值，
     * 确保租约不会超过考试截止时间。</p>
     *
     * @param deadline 考试截止时间
     * @param now      当前时间
     * @return TTL 毫秒数
     * @throws BusinessException REQUIRED_CODE — 已过截止时间
     */
    private long ttlMillis(LocalDateTime deadline, LocalDateTime now) {
        long untilDeadline = Duration.between(now, deadline).toMillis();
        if (untilDeadline <= 0) {
            throw new BusinessException(REQUIRED_CODE, "考试作答时间已结束");
        }
        return Math.min(properties.safeLeaseTimeoutSeconds() * 1_000L, untilDeadline);
    }

    /**
     * 条件删除 Redis 中的租约（仅当 token 匹配时删除）。
     * <p>使用 {@link #DELETE_SCRIPT} 保证原子性，防止误删其他客户端的新租约。
     * Redis 异常仅记录警告，不向上抛出。</p>
     */
    private void deleteRedisIfToken(Long examId, Long studentId, String leaseToken) {
        try {
            redis.execute(DELETE_SCRIPT, List.of(key(examId, studentId)), leaseToken);
        } catch (DataAccessException exception) {
            log.warn("Failed to clear Redis client lease: examId={}, studentId={}", examId, studentId, exception);
        }
    }

    /**
     * 注册事务回滚时的 Redis 清理回调。
     * <p>用于 {@link #acquire} 中新客户端接管的场景：Redis 写入在 MySQL 事务提交前完成，
     * 如果 MySQL 事务最终回滚，需要清理 Redis 中已写入的租约，避免"幽灵租约"。</p>
     */
    private void registerRollbackCleanup(Long examId, Long studentId, String leaseToken) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // 仅在事务未成功提交时清理（回滚或未知状态）
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteRedisIfToken(examId, studentId, leaseToken);
                }
            }
        });
    }

    /**
     * 校验 clientId：非空且不超过最大长度。
     */
    private void validateClientId(String clientId) {
        if (!hasText(clientId) || clientId.length() > MAX_CLIENT_ID_LENGTH) {
            throw new BusinessException(REQUIRED_CODE, "考试客户端标识缺失，请刷新考试页面后重试");
        }
    }

    /**
     * 校验 leaseToken：非空且不超过最大长度。
     */
    private void validateToken(String leaseToken) {
        if (!hasText(leaseToken) || leaseToken.length() > MAX_TOKEN_LENGTH) {
            throw new BusinessException(REQUIRED_CODE, "考试窗口租约已失效，请返回考试列表后重新进入");
        }
    }

    /**
     * 创建租约冲突异常（提示用户回到原窗口或等待租约过期）。
     */
    private BusinessException conflict() {
        return new BusinessException(CONFLICT_CODE, "本场考试已在其他窗口答题，请回到原窗口继续作答或等待租约过期后重试");
    }

    /**
     * 构建 Redis 键。
     * <p>格式：{@code exam:client-lease:{examId:studentId}}
     * 花括号确保 Redis Cluster 模式下同一 examId:studentId 的键路由到同一 slot。</p>
     */
    private String key(Long examId, Long studentId) {
        return "exam:client-lease:{" + examId + ":" + studentId + "}";
    }

    /**
     * LocalDateTime → epoch 毫秒（使用系统默认时区）。
     */
    private long epochMillis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * epoch 毫秒 → LocalDateTime（使用系统默认时区）。
     */
    private LocalDateTime localDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    /**
     * 安全地将 Redis 返回值转为 String。
     * <p>Redis 在某些序列化配置下可能返回 byte[]，此方法统一处理。</p>
     */
    private String asString(Object value) {
        return value instanceof byte[] bytes
            ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            : String.valueOf(value);
    }

    /**
     * 判断字符串是否有内容（非 null 且非空白）。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * MySQL 降级查询的结果行：只需要 sessionId 和 deadline。
     */
    private record DatabaseLeaseRow(Long sessionId, LocalDateTime deadline) {
    }
}
