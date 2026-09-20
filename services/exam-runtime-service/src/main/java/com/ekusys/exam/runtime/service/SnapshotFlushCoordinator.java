package com.ekusys.exam.runtime.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.runtime.config.SnapshotProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 快照落库协调器 —— 答题快照从 Redis 持久化到 MySQL 的核心执行逻辑。
 *
 * <h3>职责</h3>
 * <p>由 {@link SnapshotFlushScheduler} 定时驱动，本类负责三项工作：</p>
 * <ol>
 *     <li><b>主落库</b>（{@link #flushDue}）：批量认领脏快照 → 并行读取/校验/持久化 → 确认或重试。</li>
 *     <li><b>对账</b>（{@link #reconcile}）：发现并回收因宕机或竞态而遗漏的脏快照。</li>
 *     <li><b>清理</b>（{@link #cleanupFailed}）：删除多次重试仍失败的已隔离条目。</li>
 * </ol>
 *
 * <h3>处理流程（每个快照）</h3>
 * <pre>
 * claimBatch()          从 Redis Sorted Set 认领一批到期的脏快照
 *   ↓ 并行
 * queue.read(claim)     读取快照 JSON 数据和版本号，校验认领租约
 *   ↓
 * validatePayload()     校验身份一致性（examId/studentId）和答题格式
 *   ↓
 * persistence.persistDraft()  写入 MySQL（幂等 UPSERT，版本保护）
 *   ↓
 * queue.acknowledge()   从 Redis 队列中移除已落库的快照
 * </pre>
 *
 * <h3>失败分类与重试策略</h3>
 * <table>
 *     <tr><th>失败模式</th><th>触发异常</th><th>处理方式</th></tr>
 *     <tr><td>{@code POISON}</td>
 *         <td>JSON 解析失败 / 身份不匹配 / 答题校验失败</td>
 *         <td>数据本身有问题，重试无意义；标记后快速进入隔离</td></tr>
 *     <tr><td>{@code TRANSIENT}</td>
 *         <td>MySQL 等数据源瞬时不可用（{@link DataAccessException}）</td>
 *         <td>退避重试；通常随基础设施恢复而自动成功</td></tr>
 *     <tr><td>{@code RETRYABLE}</td>
 *         <td>其他运行时异常</td>
 *         <td>退避重试；重试次数耗尽后隔离</td></tr>
 * </table>
 *
 * <p>退避间隔由 {@link SnapshotFlushBackoffPolicy} 按重试次数递增计算，
 * 重试次数耗尽后条目进入隔离（quarantine），由 {@link #cleanupFailed} 最终清理。</p>
 *
 * @see SnapshotFlushScheduler 调度入口（何时触发）
 * @see SnapshotFlushQueue Redis 队列操作（认领/读取/确认/失败标记）
 * @see SnapshotPersistenceService MySQL 持久化（幂等写入）
 * @see SnapshotFlushMetrics Micrometer 指标（persisted/retry/quarantined 等计数器）
 */
@Component
public class SnapshotFlushCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SnapshotFlushCoordinator.class);

    /** Redis 队列操作：认领、读取、确认、失败标记、对账、清理 */
    private final SnapshotFlushQueue queue;

    /** MySQL 持久化服务：将答题快照幂等写入数据库 */
    private final SnapshotPersistenceService persistence;

    /** 答题输入校验器：验证答题数据格式和合法性 */
    private final ExamAnswerInputValidator validator;

    /** JSON 序列化/反序列化：解析 Redis 中存储的快照 JSON */
    private final ObjectMapper objectMapper;

    /** 退避策略：根据重试次数计算下一次重试的延迟时间 */
    private final SnapshotFlushBackoffPolicy backoffPolicy;

    /** Micrometer 指标收集器 */
    private final SnapshotFlushMetrics metrics;

    /** 快照处理专用线程池，支持批内并行处理 */
    private final Executor executor;

    /** 快照落库参数：限制单次轮询连续处理的批次数 */
    private final SnapshotProperties properties;

    public SnapshotFlushCoordinator(SnapshotFlushQueue queue,
                                    SnapshotPersistenceService persistence,
                                    ExamAnswerInputValidator validator,
                                    ObjectMapper objectMapper,
                                    SnapshotFlushBackoffPolicy backoffPolicy,
                                    SnapshotFlushMetrics metrics,
                                    SnapshotProperties properties,
                                    @Qualifier("snapshotFlushExecutor") Executor executor) {
        this.queue = queue;
        this.persistence = persistence;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.backoffPolicy = backoffPolicy;
        this.metrics = metrics;
        this.properties = properties;
        this.executor = executor;
    }

    // ==================== 三个调度入口 ====================

    /**
     * 主落库任务：批量认领并并行处理脏快照。
     *
     * <p>执行步骤：</p>
     * <ol>
     *     <li>调用 {@link SnapshotFlushQueue#claimBatch()} 从 Redis Sorted Set 中认领
     *         一批 score 已到期的脏快照（同时回收过期租约）。</li>
     *     <li>将每个认领到的 claim 提交到 {@code snapshotFlushExecutor} 线程池并行处理。</li>
     *     <li>连续认领直到没有到期数据，或达到单轮最大批次数。</li>
     *     <li>等待所有并行任务完成后，刷新积压指标。</li>
     * </ol>
     *
     * <p>如果 Redis 不可用，记录警告并跳过本轮，不抛出异常，
     * 等待下一轮调度重试。</p>
     */
    public void flushDue() {
        for (int index = 0; index < properties.safeFlushMaxBatchesPerRun(); index++) {
            SnapshotFlushClaimBatch batch;
            try {
                batch = queue.claimBatch();
            } catch (DataAccessException exception) {
                metrics.increment("redis_unavailable");
                log.warn("Redis snapshot flush queue unavailable", exception);
                break;
            }

            metrics.increment("lease_recovered", batch.recoveredLeases());
            metrics.increment("claimed", batch.claims().size());
            if (batch.claims().isEmpty()) {
                break;
            }

            List<CompletableFuture<Void>> tasks = batch.claims().stream()
                .map(claim -> CompletableFuture.runAsync(() -> process(claim), executor))
                .toList();
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        }

        refreshBacklog();
    }

    /**
     * 对账任务：发现并回收遗漏的脏快照。
     *
     * <p>某些快照可能因服务宕机、Redis 瞬时故障或竞态条件而未进入落库队列。
     * 本方法扫描 Redis 中的快照键，将遗漏的条目重新加入队列。</p>
     *
     * @return 本次对账新加入队列的快照数量
     */
    public int reconcile() {
        try {
            int added = queue.reconcilePage();
            metrics.increment("reconciled", added);
            refreshBacklog();
            return added;
        } catch (DataAccessException exception) {
            metrics.increment("redis_unavailable");
            log.warn("Redis snapshot reconciliation unavailable", exception);
            return 0;
        }
    }

    /**
     * 清理任务：删除多次重试仍然失败的已隔离快照条目。
     *
     * <p>被隔离的条目已无法自动恢复，此方法将其从 Redis 中移除以释放空间。
     * 运维人员需根据 {@code deploy/runbooks/SNAPSHOT_FLUSH_OPERATIONS.md} 进行人工排查。</p>
     *
     * @return 本次清理删除的条目数量
     */
    public int cleanupFailed() {
        try {
            int deleted = queue.cleanupFailed();
            metrics.increment("failed_cleaned", deleted);
            refreshBacklog();
            return deleted;
        } catch (DataAccessException exception) {
            metrics.increment("redis_unavailable");
            log.warn("Redis snapshot failed-entry cleanup unavailable", exception);
            return 0;
        }
    }

    // ==================== 单个快照处理 ====================

    /**
     * 处理单个被认领的快照：读取 → 校验 → 持久化 → 确认。
     *
     * <p>处理流程和分支：</p>
     * <ol>
     *     <li><b>读取</b>：从 Redis 读取快照数据和版本号。
     *         如果认领租约已失效（被其他实例抢走），直接跳过。</li>
     *     <li><b>空快照</b>：快照数据不存在（可能已被前端覆盖或过期清理），
     *         确认移除后跳过。</li>
     *     <li><b>解析与校验</b>：
     *         解析 member（格式 "examId:studentId"）得到 {@link SnapshotIdentity}，
     *         反序列化 JSON 得到 {@link SnapshotPayload}，
     *         校验身份一致性和答题数据合法性。</li>
     *     <li><b>持久化</b>：调用 {@link SnapshotPersistenceService#persistDraft}
     *         将答题数据写入 MySQL。该操作是幂等的，通过版本号防止旧数据覆盖新数据。</li>
     *     <li><b>版本一致性检查</b>：如果 MySQL 返回的已持久化版本低于快照版本，
     *         说明写入异常，抛出 {@link IllegalStateException}。</li>
     *     <li><b>确认</b>：调用 {@link SnapshotFlushQueue#acknowledge} 从队列中移除。
     *         返回值区分正常确认（result=1）和期间有更新版本写入（result=2）的情况。</li>
     * </ol>
     *
     * <p>异常按类型分为三个失败通道：</p>
     * <ul>
     *     <li>{@code POISON} — JSON 解析/身份校验/业务校验失败，数据本身有问题</li>
     *     <li>{@code TRANSIENT} — 数据源瞬时不可用，退避后通常可恢复</li>
     *     <li>{@code RETRYABLE} — 其他运行时异常，退避重试</li>
     * </ul>
     *
     * @param claim 认领凭据（member + leaseToken）
     */
    private void process(SnapshotFlushClaim claim) {
        // 第一步：从 Redis 读取快照数据
        SnapshotFlushRead read;
        try {
            read = queue.read(claim);
        } catch (DataAccessException exception) {
            // Redis 读取失败：放弃本条，租约过期后会被自动回收
            metrics.increment("redis_unavailable");
            log.warn("Snapshot flush read failed; lease will recover: member={}", claim.member(), exception);
            return;
        }

        // 认领租约已失效（被其他实例抢走），跳过
        if (!read.leaseValid()) {
            metrics.increment("stale");
            return;
        }

        // 快照数据不存在（已被覆盖或过期清理），确认移除
        if (read.payload() == null || read.version() == null) {
            if (queue.acknowledgeMissing(claim)) {
                metrics.increment("missing");
            } else {
                metrics.increment("stale");
            }
            return;
        }

        // 第二步：解析、校验、持久化、确认
        try {
            // 解析 member 字符串（"examId:studentId"）为身份标识
            SnapshotIdentity identity = SnapshotIdentity.parse(claim.member());

            // 反序列化 JSON 为快照载荷
            SnapshotPayload payload = objectMapper.readValue(read.payload(), SnapshotPayload.class);

            // 校验身份一致性和答题数据格式
            validatePayload(identity, payload, read.version());

            // 持久化到 MySQL（幂等 UPSERT，版本保护）
            long persistedVersion = persistence.persistDraft(
                identity.examId(), identity.studentId(), payload.answers(), payload.snapshotVersion(),
                parseReceivedAt(payload.serverReceivedAt())
            );

            // 版本一致性检查：MySQL 返回的版本应 >= 快照版本
            if (persistedVersion >= 0 && persistedVersion < payload.snapshotVersion()) {
                throw new IllegalStateException("MySQL draft version did not cover snapshot");
            }

            // 从 Redis 队列中确认移除
            // result: 0=租约已失效(stale) 1=正常确认 2=期间有更新版本写入
            long result = queue.acknowledge(claim, payload.snapshotVersion());
            if (result == 0) {
                metrics.increment("stale");
            } else {
                metrics.increment("persisted");
                if (result == 2) {
                    // 落库期间客户端又提交了更新版本，本次落库仍然有效，新版本等下一轮处理
                    metrics.increment("newer_version");
                }
            }
        } catch (JsonProcessingException | SnapshotPayloadException | BusinessException exception) {
            // 数据中毒：JSON 格式错误、身份不匹配、答题校验失败 → 重试无意义
            handleFailure(claim, read.version(), exception, SnapshotFailureMode.POISON);
        } catch (DataAccessException exception) {
            // 数据源瞬时不可用（MySQL/Redis）→ 退避后通常可恢复
            handleFailure(claim, read.version(), exception, SnapshotFailureMode.TRANSIENT);
        } catch (RuntimeException exception) {
            // 其他运行时异常 → 退避重试
            handleFailure(claim, read.version(), exception, SnapshotFailureMode.RETRYABLE);
        }
    }

    // ==================== 校验与辅助方法 ====================

    /**
     * 校验快照载荷的完整性和一致性。
     *
     * <p>校验内容：</p>
     * <ul>
     *     <li>examId 和 studentId 非空且与 Redis 队列 member 解析出的身份一致。</li>
     *     <li>snapshotVersion 为正数且与 Redis 存储的版本号一致。</li>
     *     <li>答题数据通过 {@link ExamAnswerInputValidator#validateAnswers} 格式校验。</li>
     * </ul>
     *
     * @param identity       从 member 解析的身份标识
     * @param payload        反序列化后的快照载荷
     * @param claimedVersion Redis 中存储的版本号
     * @throws SnapshotPayloadException 身份或版本不匹配
     * @throws BusinessException        答题数据格式不合法
     */
    private void validatePayload(SnapshotIdentity identity, SnapshotPayload payload, long claimedVersion) {
        if (payload.examId() == null || payload.studentId() == null
            || !payload.examId().equals(identity.examId())
            || !payload.studentId().equals(identity.studentId())
            || payload.snapshotVersion() <= 0
            || payload.snapshotVersion() != claimedVersion) {
            throw new SnapshotPayloadException("快照身份或版本不匹配");
        }
        validator.validateAnswers(payload.answers());
    }

    /**
     * 解析快照中的服务端接收时间字符串。
     *
     * @param value ISO-8601 格式的时间字符串，可为 null
     * @return 解析后的 LocalDateTime，如果输入为 null 则返回 null
     * @throws SnapshotPayloadException 时间格式非法
     */
    private java.time.LocalDateTime parseReceivedAt(String value) {
        try {
            return value == null ? null : java.time.LocalDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new SnapshotPayloadException("非法快照接收时间");
        }
    }

    /**
     * 统一处理快照落库失败。
     *
     * <p>执行步骤：</p>
     * <ol>
     *     <li>查询当前 member 的累计重试次数。</li>
     *     <li>根据重试次数通过 {@link SnapshotFlushBackoffPolicy} 计算退避延迟。</li>
     *     <li>调用 {@link SnapshotFlushQueue#markFailure} 在 Redis 中标记失败，
     *         更新 score（延迟到下一次可重试的时刻）。</li>
     *     <li>根据标记结果记录指标和日志：
     *         <ul>
     *             <li>{@code stale} — 租约已失效，不再是本实例的责任</li>
     *             <li>{@code quarantined} — 重试次数耗尽，已隔离（error 级别日志）</li>
     *             <li>{@code retry} — 已安排退避重试（warn 级别日志）</li>
     *         </ul>
     *     </li>
     * </ol>
     *
     * @param claim     认领凭据
     * @param version   快照版本号
     * @param exception 导致失败的异常
     * @param mode      失败分类（POISON/TRANSIENT/RETRYABLE）
     */
    private void handleFailure(SnapshotFlushClaim claim, long version,
                               Throwable exception, SnapshotFailureMode mode) {
        try {
            // 查询累计重试次数，计算退避延迟
            int attempt = queue.nextAttempt(claim.member());
            long delay = backoffPolicy.delayMillis(attempt);

            // 在 Redis 中标记失败并更新下次可重试时间
            SnapshotFailureResult result = queue.markFailure(claim, version, exception, mode, delay);

            if (!result.updated() || result.stale()) {
                // 租约已失效或条目已过时
                metrics.increment("stale");
            } else if (result.quarantined()) {
                // 重试次数耗尽，已隔离 → error 级别日志，需要运维介入
                metrics.increment("quarantined");
                log.error("Snapshot flush quarantined: member={}, version={}, attempts={}",
                    claim.member(), version, result.attempts(), exception);
            } else {
                // 已安排退避重试 → warn 级别日志
                metrics.increment("retry");
                log.warn("Snapshot flush retry scheduled: member={}, version={}, attempts={}",
                    claim.member(), version, result.attempts(), exception);
            }
        } catch (DataAccessException redisException) {
            // 标记失败本身也失败了（Redis 不可用），放弃记录，租约过期后会被自动回收
            metrics.increment("redis_unavailable");
            log.warn("Snapshot flush failure could not be recorded; lease will recover: member={}",
                claim.member(), redisException);
        }
    }

    /**
     * 刷新积压指标：从 Redis 读取当前 dirty/processing/failed 队列长度，
     * 更新 Micrometer gauge 供监控看板展示。
     */
    private void refreshBacklog() {
        try {
            metrics.updateBacklog(queue.backlog());
        } catch (DataAccessException exception) {
            metrics.increment("redis_unavailable");
            log.debug("Snapshot flush backlog metrics unavailable", exception);
        }
    }

    // ==================== 内部类型 ====================

    /**
     * 从 Redis 队列 member 字符串解析出的快照身份标识。
     * <p>member 格式为 {@code "examId:studentId"}，例如 {@code "42:1001"}。</p>
     *
     * @param examId    考试 ID
     * @param studentId 学生 ID
     */
    private record SnapshotIdentity(Long examId, Long studentId) {
        /**
         * 从 member 字符串解析身份标识。
         *
         * @param member 格式为 "examId:studentId" 的字符串，两部分都必须是正整数
         * @return 解析后的身份标识
         * @throws SnapshotPayloadException 格式不合法
         */
        private static SnapshotIdentity parse(String member) {
            if (member == null || !member.matches("[1-9]\\d*:[1-9]\\d*")) {
                throw new SnapshotPayloadException("非法快照成员");
            }
            String[] parts = member.split(":", 2);
            try {
                return new SnapshotIdentity(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
            } catch (NumberFormatException exception) {
                throw new SnapshotPayloadException("非法快照成员");
            }
        }
    }

    /**
     * 快照载荷解析/校验过程中的内部异常。
     * <p>用于标记"数据本身有问题"的情况（相对于基础设施问题），
     * 在 {@link #process} 中被归类为 {@link SnapshotFailureMode#POISON}。</p>
     */
    private static final class SnapshotPayloadException extends RuntimeException {
        private SnapshotPayloadException(String message) {
            super(message);
        }
    }
}
