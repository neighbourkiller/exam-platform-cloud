package com.ekusys.exam.exam.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 答题快照定时落库调度器（旧单体版本）。
 *
 * <p>学生在考试作答过程中，答题数据先以快照形式暂存在 Redis 中（快路径），
 * 以保证高频写入的性能。本调度器负责定时将 Redis 中的快照增量数据
 * 批量持久化到 MySQL 数据库，确保数据不丢失。</p>
 *
 * <h3>调度机制</h3>
 * <ul>
 *     <li>使用 Spring 的 {@code @Scheduled(fixedDelay)} 注解，
 *         以固定延迟（上一次执行完成后等待指定时间）的方式周期性触发。</li>
 *     <li>默认间隔 30 秒，可通过配置项 {@code app.snapshot.flush-interval-ms} 自定义。</li>
 *     <li>{@code fixedDelay}（非 {@code fixedRate}）确保上一轮落库完成后才开始计时，
 *         避免在落库耗时较长时出现任务堆叠。</li>
 * </ul>
 *
 * <h3>调用链路</h3>
 * <pre>
 * SnapshotFlushScheduler.flushSnapshots()
 *   → ExamService.flushAllSnapshotsToDatabase()
 *     → ExamSnapshotService.flushAllSnapshotsToDatabase()
 * </pre>
 *
 * <p><b>注意</b>：此类位于旧单体代码路径 {@code src/main/java} 下。
 * 微服务版本的快照落库调度器位于
 * {@code services/exam-runtime-service} 模块中的 {@code SnapshotFlushScheduler}，
 * 功能更完善（包含对账和失败清理任务）。</p>
 *
 * @see ExamService#flushAllSnapshotsToDatabase()
 */
@Component
public class SnapshotFlushScheduler {

    /** 考试业务服务，提供快照落库的实际执行入口 */
    private final ExamService examService;

    public SnapshotFlushScheduler(ExamService examService) {
        this.examService = examService;
    }

    /**
     * 定时将 Redis 中的答题快照批量刷入 MySQL。
     *
     * <p>每次执行会扫描 Redis 中所有待持久化的快照数据，
     * 逐个（或批量）写入 MySQL 的考试答题表。
     * 执行间隔由 {@code app.snapshot.flush-interval-ms} 配置，默认 30000 毫秒（30 秒）。</p>
     *
     * <p>使用 {@code fixedDelay} 而非 {@code fixedRate}，
     * 保证前一次落库任务完成后才开始下一轮计时，避免并发执行。</p>
     */
    @Scheduled(fixedDelayString = "${app.snapshot.flush-interval-ms:30000}")
    public void flushSnapshots() {
        examService.flushAllSnapshotsToDatabase();
    }
}
