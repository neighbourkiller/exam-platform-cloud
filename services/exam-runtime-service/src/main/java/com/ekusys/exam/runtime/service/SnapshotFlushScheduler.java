package com.ekusys.exam.runtime.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 答题快照定时落库调度器（微服务版本）。
 *
 * <h3>背景</h3>
 * <p>学生考试作答时，答题数据先以快照形式写入 Redis（快路径），
 * 本调度器负责定时将这些 Redis 快照增量数据批量持久化到 MySQL，
 * 确保答题数据最终可靠落库。</p>
 *
 * <h3>三个定时任务</h3>
 * <table>
 *     <tr><th>任务</th><th>默认间隔</th><th>职责</th></tr>
 *     <tr><td>{@link #flushSnapshots()}</td><td>30 秒轮询</td>
 *         <td>主落库任务：认领已等待 15 分钟的脏快照，并行写入 MySQL</td></tr>
 *     <tr><td>{@link #reconcileSnapshots()}</td><td>5 分钟</td>
 *         <td>对账任务：发现并回收因宕机或异常而遗漏的脏快照</td></tr>
 *     <tr><td>{@link #cleanupFailedSnapshots()}</td><td>1 小时</td>
 *         <td>清理任务：删除多次重试仍然失败的"毒药"快照条目</td></tr>
 * </table>
 *
 * <h3>调度设计</h3>
 * <ul>
 *     <li>三个任务都使用 {@code fixedDelay}（非 {@code fixedRate}），
 *         保证上一轮完成后才开始下一轮计时，避免任务堆叠。</li>
 *     <li>对账和清理任务额外配置了 {@code initialDelay}，
 *         服务启动后延迟一段时间再首次执行，避免启动时与主落库任务争抢资源。</li>
 *     <li>三个任务共享独立的调度线程池 {@code snapshotFlushTaskScheduler}
 *         （在 {@link com.ekusys.exam.runtime.config.SnapshotFlushConfiguration} 中定义），
 *         与 Spring 默认的 {@code @Scheduled} 单线程池隔离，互不阻塞。</li>
 * </ul>
 *
 * <p>本调度器仅负责"何时触发"，实际的落库、对账、清理逻辑全部委托给
 * {@link SnapshotFlushCoordinator}，保持调度与业务的职责分离。</p>
 *
 * @see SnapshotFlushCoordinator 落库/对账/清理的实际执行者
 * @see com.ekusys.exam.runtime.config.SnapshotFlushConfiguration 调度线程池与相关 Bean 配置
 */
@Component
public class SnapshotFlushScheduler {

    /** 快照落库协调器，封装了认领、持久化、重试、对账和清理的全部逻辑 */
    private final SnapshotFlushCoordinator coordinator;

    public SnapshotFlushScheduler(SnapshotFlushCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * 主落库任务：将 Redis 中的脏快照批量刷入 MySQL。
     *
     * <p>每次执行时，{@link SnapshotFlushCoordinator#flushDue()} 会：</p>
     * <ol>
     *     <li>从 Redis Sorted Set 中认领一批到期的脏快照（claim）。</li>
     *     <li>通过线程池并行处理每个快照：读取 → 校验 → 写入 MySQL → 确认完成。</li>
     *     <li>处理失败的快照会根据失败类型（数据中毒/瞬态/可重试）
     *         进入退避重试或隔离队列。</li>
     *     <li>刷新积压指标（dirty/processing/failed 数量）供监控使用。</li>
     * </ol>
     *
     * <p>配置项：{@code app.snapshot.flush-poll-interval-ms}，默认 30000 毫秒（30 秒）。
     * 快照首次变脏后的落库延迟由 {@code app.snapshot.flush-interval-ms} 控制，默认 15 分钟。</p>
     */
    @Scheduled(
        fixedDelayString = "${app.snapshot.flush-poll-interval-ms:30000}",
        scheduler = "snapshotFlushTaskScheduler"
    )
    public void flushSnapshots() {
        coordinator.flushDue();
    }

    /**
     * 对账任务：发现并回收遗漏的脏快照。
     *
     * <p>某些快照可能因为以下原因滞留在 Redis 中未被主落库任务处理：</p>
     * <ul>
     *     <li>持有认领租约的服务实例宕机，租约未被释放。</li>
     *     <li>Redis 瞬时故障导致个别快照未被加入队列。</li>
     *     <li>竞态条件导致快照入队时机与认领窗口错开。</li>
     * </ul>
     *
     * <p>{@link SnapshotFlushCoordinator#reconcile()} 会扫描 Redis 中的快照键，
     * 将遗漏的条目重新加入落库队列，确保最终一致性。</p>
     *
     * <p>配置项：{@code app.snapshot.flush-reconcile-interval-ms}，默认 300000 毫秒（5 分钟）。
     * 首次执行延迟同样为 5 分钟，避免服务启动初期的资源争抢。</p>
     */
    @Scheduled(
        fixedDelayString = "${app.snapshot.flush-reconcile-interval-ms:300000}",
        initialDelayString = "${app.snapshot.flush-reconcile-interval-ms:300000}",
        scheduler = "snapshotFlushTaskScheduler"
    )
    public void reconcileSnapshots() {
        coordinator.reconcile();
    }

    /**
     * 清理任务：删除多次重试仍然失败的快照条目。
     *
     * <p>当某个快照因数据格式异常（"毒药消息"）或持续的数据库错误导致
     * 重试次数耗尽后，会被标记为"已隔离（quarantined）"。
     * 本任务负责定期清理这些已隔离的条目，释放 Redis 空间。</p>
     *
     * <p>被清理的快照数据已无法自动恢复，需要通过运维手段
     * （参见 deploy/runbooks/SNAPSHOT_FLUSH_OPERATIONS.md）进行人工排查和重放。</p>
     *
     * <p>配置项：{@code app.snapshot.flush-cleanup-interval-ms}，默认 3600000 毫秒（1 小时）。
     * 首次执行延迟同样为 1 小时。</p>
     */
    @Scheduled(
        fixedDelayString = "${app.snapshot.flush-cleanup-interval-ms:3600000}",
        initialDelayString = "${app.snapshot.flush-cleanup-interval-ms:3600000}",
        scheduler = "snapshotFlushTaskScheduler"
    )
    public void cleanupFailedSnapshots() {
        coordinator.cleanupFailed();
    }
}
