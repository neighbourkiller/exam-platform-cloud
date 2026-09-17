# 超时自动交卷 V2 运维说明

## 分片模型：V1、旧任务池 V2 与"分片优先＋跨片恢复" V2（当前版本）

| 模式 | 领取方式 | 说明 |
|---|---|---|
| V1（`app.timeout-submission.enabled=false`） | `exam_session` 按 `MOD(id, shardTotal) = shardIndex` 固定分片扫描 | 无任务表与租约，分片参数直接过滤会话。 |
| 旧任务池 V2（升级前） | `SHARDING_BROADCAST` 唤醒后，所有实例竞争同一任务池 | 分片参数在 Service 层被丢弃；互斥完全依赖行锁、租约令牌和最终化校验。 |
| 分片优先＋跨片恢复（当前版本） | 四阶段混合领取：①全局恢复过期租约（不取模）→②本片到期 `PENDING`（为跨片预留 `ceil(R/2)`）→③跨片 `PENDING` 补领（`available_at` 已超过 `cross-shard-delay-ms`）→④本片补足（排除已选 ID） | 单实例跳过跨片预留；互斥仍依赖行锁、租约令牌和最终化校验。故障分片的任务无需等待执行器摘除即可由存活实例接管。 |

当前版本的补充语义：

- 参数校验：`shardTotal < 1`、`shardIndex < 0` 或 `shardIndex >= shardTotal` 会在访问数据库前抛出
  `IllegalArgumentException` 并使该次执行失败，不会静默退化为全表领取；单实例固定使用 `(0, 1)`。
- 跨片兜底等待：`app.timeout-submission.cross-shard-delay-ms`（环境变量
  `APP_TIMEOUT_SUBMISSION_CROSS_SHARD_DELAY_MS`，默认 10000，最小 1000）表示其他分片 `PENDING`
  任务自 `available_at` 起等待多久后允许补领；它不是故障恢复 SLA 保证。
- 分片只约束领取阶段：已领取任务继续按原租约令牌处理，续租、最终化、失败退避不增加分片条件；
  扩缩容交叠期间仍以行锁、租约令牌与最终化校验保护写入。
- 每轮汇总日志包含分片参数、本片/跨片 PENDING 领取量、本片/跨片过期租约恢复量与完成量；
  指标 `claim_own_pending`、`claim_cross_pending`、`claim_lease_recovered_own`、
  `claim_lease_recovered_cross` 按成功领取计数。
- 分片键是任务表 `id`；任务创建逻辑使其与会话 `id` 一致。动态 `MOD` 无法直接利用现有索引定位分片，
  补领不构成性能提升依据，容量结论仍以容量验收为准。

## 可靠性边界

XXL-JOB 只负责唤醒 `examTimeoutSubmitJob`。Runtime 使用 MySQL
`submission_timeout_task`、租约令牌和 `FOR UPDATE SKIP LOCKED` 协调多实例消费。
最终答案、Submission、Session、任务状态和 `SubmissionAccepted` Outbox 在同一个
Runtime 本地事务中提交；RabbitMQ 之后仍是至少一次投递，消费端必须保持幂等。
默认任务租约为 30 秒、续租间隔为 10 秒、单任务硬预算为 20 秒；这些值只为
`<60s` 的实例退出恢复留出理论余量，不能替代实际故障注入验收。

本次 SLA 截止点是 Runtime 本地事务完成，不包含 RabbitMQ Confirm、自动判分或
Reporting 投影完成。

## 上线顺序

1. 备份 `exam_runtime`，先发布包含 `V8__timeout_submission_v2.sql`、
   `V10__optimize_timeout_submission_claim.sql` 和
   `V11__harden_timeout_submission_finalization.sql` 的 Runtime 兼容版本，保持
   `APP_TIMEOUT_SUBMISSION_V2_ENABLED=false`。迁移会建立任务表、最终答案表、数据库草稿
   载荷和永久失败字段，并建立按 `available_at` 排序的专用领取索引。
2. 确认所有 Runtime 实例都已升级并完成兼容读写；随后发布包含
   `V6__project_submission_finalization.sql` 的 Reporting 兼容消费者，最后发布使用
   `serverEpochMs/serverRevision` 的前端。旧事件消费者必须能忽略 V2 事件新增字段。
3. 在 XXL-JOB 控制台暂停 `examTimeoutSubmitJob`，等待现有执行结束。
4. 检查缺失任务并等待查询结果为零：

```sql
SELECT COUNT(*) AS missing_tasks
FROM exam_session s
LEFT JOIN submission_timeout_task t ON t.session_id = s.id
WHERE s.status IN ('ANSWERING', 'AUTO_SUBMITTING')
  AND t.id IS NULL;
```

5. 只有真实 MySQL/Redis/RabbitMQ/XXL-Job 故障注入、两实例和四实例 10,000 人同刻截止
   验收全部通过，且没有异常 `FAILED/PROCESSING`、告警接收有效时，才在
   `.env.microservices` 设置 `APP_TIMEOUT_SUBMISSION_V2_ENABLED=true`。如果同时修改
   `deploy/nacos-config/exam-runtime-service.yml`，先通过唯一 Compose 入口强制重建
   `nacos-config-init`，再重建全部 Runtime 容器。线程池和 Hikari 参数在 Bean/连接池
   初始化时生效，因此调整这些参数后必须滚动重启，不能只依赖动态刷新。逐实例通过受控的
   容器配置检查、`Timeout submission initialized: mode=...` 启动日志和 XXL-Job 执行日志
   确认最终有效值，禁止暴露 `/actuator/env`，也禁止打印密钥类环境变量。

```bash
docker compose -p exam-platform-cloud -f docker-compose.yml --env-file .env.microservices \
  up --no-deps --force-recreate nacos-config-init
docker compose -p exam-platform-cloud -f docker-compose.yml --env-file .env.microservices \
  up -d --force-recreate runtime-service
```
6. 将现有 XXL-JOB 的 CRON 更新为 `0/2 * * * * ?`，保留
   `SHARDING_BROADCAST`、`SERIAL_EXECUTION` 和 `DO_NOTHING`，然后重新启用。
7. 观察积压、最老逾期、失败数、数据库连接池等待和 Outbox 积压。

`deploy/mysql/init/02-configure-xxl-job.sh` 只会影响新 MySQL 数据卷；已有环境必须在
XXL-JOB 控制台修改 CRON，禁止通过删除数据卷重新初始化。

### 严格分片补丁的发布步骤

本次将 V2 领取 SQL 收紧为严格分片，无 Schema 变更。发布窗口内按以下顺序执行：

1. 在 XXL-JOB 控制台暂停 `examTimeoutSubmitJob`，等待所有在途执行退出；通过 Runtime 日志
   确认没有仍在运行的超时交卷工作线程（可观察 `exam.timeout.submission.worker.active` 归零）。
2. 统一升级全部 Runtime 实例到包含严格分片领取的版本；禁止新旧领取语义长期混跑。
3. 在 XXL-JOB 执行器注册列表中确认全部实例已重新注册，然后恢复 `SHARDING_BROADCAST` 触发。
4. 联调确认每轮广播覆盖有效执行器及完整分片下标：Runtime 日志中协调器每轮汇总
   `Timeout submission round finished: shardIndex=..., shardTotal=..., claimed=..., completed=...`
   的 `shardTotal` 应与执行器注册数一致；故障摘除后的新一轮总数同样必须与注册列表一致。
5. 保留 `SHARDING_BROADCAST`、`SERIAL_EXECUTION`、`DO_NOTHING` 和现有调度周期，不新增调度器
   或 Compose 入口。分片补齐不自动开启 V2，也不豁免容量、故障注入和告警验收门槛。

## 状态检查

```sql
SELECT status, COUNT(*) AS task_count, MIN(due_at) AS oldest_due_at,
       MAX(attempt_count) AS max_attempts
FROM submission_timeout_task
GROUP BY status;

SELECT id, session_id, exam_id, student_id, due_at, available_at, status,
       attempt_count, lease_until, next_retry_at, failure_code, incident_id,
       failed_at, replay_count, last_error
FROM submission_timeout_task
WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')
ORDER BY available_at, due_at, id
LIMIT 200;

SELECT s.id, s.exam_id, s.student_id, s.status AS session_status,
       t.status AS task_status, sub.status AS submission_status,
       fp.submission_id IS NOT NULL AS has_final_payload
FROM exam_session s
LEFT JOIN submission_timeout_task t ON t.session_id = s.id
LEFT JOIN submission sub
  ON sub.exam_id = s.exam_id AND sub.student_id = s.student_id
LEFT JOIN submission_final_payload fp ON fp.submission_id = sub.id
WHERE s.status IN ('ANSWERING', 'AUTO_SUBMITTING')
ORDER BY s.deadline_time, s.id
LIMIT 200;
```

重点指标：

- `exam.timeout.submission.backlog{state=...}`：PENDING、PROCESSING、FAILED 数量；
- `exam.timeout.submission.oldest.overdue`：最老任务逾期毫秒数；
- `exam.timeout.submission.completion.latency`：从 `due_at` 到本地完成的延迟；
- `exam.timeout.submission.finalization`：单任务处理耗时；
- `exam.timeout.submission.events`：claimed、completed、retry、failed、stale_claim、
  lease_recovered 等结果；
- `exam.timeout.submission.worker.active/queue`：工作池活跃数和排队数。

建议告警：P99 完成延迟超过 30 秒、最老逾期超过 30 秒、FAILED 大于零、
`lease_recovered` 持续增长、Hikari pending 持续非零。

增加 Runtime 工作线程或 Hikari 前，必须先按 MySQL `max_connections` 为全部微服务、迁移、
监控和运维会话预留全局连接预算。禁止只调大单个 Runtime 连接池；隔离压测中 4 实例、
每实例 32 连接已使默认 151 全局连接上限出现 `Too many connections`。

`/actuator/prometheus` 已暴露 Runtime 指标，接入生产监控系统后再配置上述聚合告警。
抓取端必须走受控内网并携带有效认证，不得把该端点改为公网匿名访问。仓库不包含
生产告警接收人和通知凭据，这些内容不得写入源码。

## 容量验收

使用 [`deploy/load-test/timeout-submission.js`](load-test/timeout-submission.js) 和
[`deploy/load-test/README.md`](load-test/README.md) 依次执行两实例、四实例、10,000 会话同一
`due_at` 的验收。k6 结果是客户端观察值；最终 SLA 还要按数据库
`due_at -> completed_at` 复核：

```sql
SET @exam_id = ?;

WITH latencies AS (
    SELECT TIMESTAMPDIFF(MICROSECOND, due_at, completed_at) / 1000 AS latency_ms
    FROM submission_timeout_task
    WHERE exam_id = @exam_id AND status = 'DONE'
), ranked AS (
    SELECT latency_ms,
           ROW_NUMBER() OVER (ORDER BY latency_ms) AS row_num,
           COUNT(*) OVER () AS done_count
    FROM latencies
)
SELECT
    (SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id = @exam_id) AS total_tasks,
    COUNT(*) AS done_tasks,
    MAX(CASE WHEN row_num = CEIL(done_count * 0.99) THEN latency_ms END) AS p99_ms,
    MAX(latency_ms) AS max_ms
FROM ranked;
```

该查询使用 MySQL 8 窗口函数；不得用平均值代替 P99。
学生端和 k6 的状态轮询应在截止后先等待带抖动的首次间隔，避免与最终化同刻形成请求尖峰；
但客户端只是观察者，验收结论仍以上述数据库延迟为准。

## 安全重放

先修复 `last_error/failure_code` 对应原因并暂停 XXL-JOB。禁止手工更新任务状态；由
ADMIN 或该考试发布教师调用审计接口：

```text
POST /api/v1/exams/{examId}/timeout-submissions/{taskId}/replay
```

接口会在同一事务内锁定并校验 Task=`FAILED`、Session=`SUBMISSION_FAILED`、
Submission=`IN_PROGRESS`，且不存在最终载荷和逻辑 `SubmissionAccepted` Outbox；校验通过后
仅将当前任务恢复为可领取并增加 `replay_count`，不会直接生成交卷结果。重复重放、越权重放
或已经形成最终结果都会拒绝，并由现有操作审计记录操作者、考试和任务。不得手工复制答案、
最终载荷或 Outbox 事件。

## 回滚

### 回滚严格分片补丁

1. 暂停 XXL-JOB，等待在途执行退出、工作线程结束（含续租中的任务完成或租约到期）。
2. 将全部 Runtime 实例恢复到上一版 V2 任务池领取代码（无分片过滤的 `lockClaimable`），
   保持原有 V2 配置与 `SHARDING_BROADCAST` 路由不变；任务表、租约令牌和 Outbox 结构无需回退。
3. 恢复调度前在 XXL-JOB 执行器注册列表确认实例数量，避免半旧拓扑下的分片总数错配。
4. 不要把关闭 V2 或切换 V1 当作本次分片改动的默认回滚方式；仅在 V2 本身需要回退时才按
   下述 V2 回滚流程执行。

### 回滚 V2

1. 暂停 XXL-JOB，并将 `app.timeout-submission.enabled=false` 发布到所有实例；使用环境
   变量占位符时，将 `APP_TIMEOUT_SUBMISSION_V2_ENABLED=false` 注入后滚动重启。
2. 等待已领取任务完成或租约到期，确认没有仍在运行的 V2 工作线程。
3. 只能回退到具备最终答案双读能力的版本；新增表保持不动，不回滚或删除 Flyway。
4. 如需临时恢复旧扫描，确认所有实例均处于关闭 V2 的同一版本后再启用 XXL-JOB。

不要让 V1 固定分片写入和 V2 任务租约写入在不同 Runtime 版本中长期混跑。
