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

## 截止语义与答案同步

Runtime 在持有 Session 和 Submission 相关锁后重新读取 MySQL
`CURRENT_TIMESTAMP(3)`。快照只有在该时间早于截止时间、会话仍为
`ANSWERING`、客户端租约和版本校验均通过时才会写入；`accepted_at` 记录这次锁内验收时间。
规范化、压缩和 SHA-256 计算发生在事务外，事务失败不视为已验收。

主动交卷和超时交卷在取得各自锁后也会重新读取数据库时间，并保留 Task 的租约令牌、Session
状态和最终条件更新作为最后校验。锁等待跨过截止时间时，最终化只能拒绝或交给可恢复状态，不能
按锁等待前的时间接受写入。

学生端常态每 30 秒执行一次同步兜底；截止前 60 秒内只有答案发生变化时才启用 500 毫秒防抖，
连续输入最长等待 2 秒。实时同步和离线重放共用一个完整快照发送通道，同一会话最多一个请求在途，
等待中的内容只保留最新完整快照。重试沿用原序列和原内容；服务端版本冲突后更新版本基线并为当前
答案生成新序列。ACK 只确认该请求携带的本地编辑版本，发送期间的新编辑仍保持 dirty。

截止判断使用客户端 `serverClock.isExpired()` 的服务端时钟样本。截止后不再发送答案快照；周期刷新
只会将未确认的最新答案保存为七天本地证据并标记为终态，同时继续查询最终交卷状态。防作弊事件仍
保持独立队列语义。

## 有界自愈与并发容量

V2 的 `examTimeoutSubmitJob` 每个实例沿用 XXL-JOB 广播调度，并在现有处理轮次末尾执行低频对账：
默认间隔 30 秒、每页最多 100 个候选、单轮最多 1 秒。游标按当前分片拓扑保存，分片总数或下标变化
时重置，页结束后回扫。

自动修复只接受同时满足以下条件的候选：会话已截止且状态为 `ANSWERING` 或 `AUTO_SUBMITTING`、
任务缺失、Submission 为 `IN_PROGRESS`，并且没有最终答案载荷或逻辑
`SubmissionAccepted` Outbox。每个候选独立短事务，顺序为幂等插入 Task、锁 Task、锁 Session、
复核 Submission 和数据库时间；复核失败会回滚本次插入。已有 `FAILED` 任务仍使用授权重放接口，
其他不一致组合只记录告警和对账计数，不自动改写最终状态。

工作池按完成队列补领，空闲槽位完成后才领取下一项；取消 Future 不会提前释放仍在执行的槽位。
任务拒绝或取消只允许原租约令牌更新失败状态，数据库不可用时停止继续补领，交给租约恢复流程。
最终化事务使用 `app.timeout-submission.finalization-transaction-timeout-ms`，默认 5000 毫秒。
本轮不调整 Hikari 连接池。

内部对账配置如下，均位于 `app.timeout-submission`，不新增公开接口或事件字段：

| 配置 | 默认值 | 作用 |
|---|---:|---|
| `reconcile-enabled` | `true` | 是否在 V2 处理轮次执行有界对账 |
| `reconcile-interval-ms` | `30000` | 同一实例两次对账的最小间隔 |
| `reconcile-batch-size` | `100` | 单页候选上限，运行时强制不超过 100 |
| `reconcile-max-run-ms` | `1000` | 单次对账预算 |
| `finalization-transaction-timeout-ms` | `5000` | 最终化事务超时 |

## 新增监控含义

除原有积压和完成延迟外，Runtime 暴露以下指标：

- `exam.timeout.submission.reconcile.last.trigger`：最近一次对账触发时间的 epoch 毫秒；
- `exam.timeout.submission.reconcile.last.success`：最近一次完整结束的对账时间的 epoch 毫秒；
- `exam.timeout.submission.reconcile.overdue`：积压查询中已逾期的 PENDING/PROCESSING 数量；
- `exam.timeout.submission.events{outcome=reconcile_repaired|reconcile_skipped|reconcile_failed}`：
  对账修复、跳过和失败次数；
- `exam.timeout.submission.completion.latency`：仅在最终事务提交成功后，从 `due_at` 到
  `completed_at` 的延迟。

指标不携带学生、任务或考试 ID。候选的会话、任务和状态只写入结构化日志。对账预算耗尽不会更新
`last.success`，因此可以用该时间戳识别调度停止或持续超时。

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

使用 [`deploy/load-test/scenarios/timeout-submission.js`](../load-test/scenarios/timeout-submission.js) 和
[`deploy/load-test/README.md`](../load-test/README.md) 依次执行两实例、四实例、10,000 会话同一
`due_at` 的验收。k6 结果是客户端观察值；最终 SLA 还要按数据库
`due_at -> completed_at` 复核：

### 本轮实际验证记录

2026-09-19 在 WSL `FedoraLinux-44`（Docker Server 29.7.2、JDK 21）执行 Runtime
模块及其依赖的 Maven 测试，结果为 170 个测试通过，0 失败、0 错误、0 跳过；其中包含真实
MySQL/Testcontainers 的锁等待、截止围栏、主动与超时交卷、自愈和最终结果保护场景。
前端 Vitest 19/19、交卷流程 8/8、入场流程 4/4，生产构建均通过。

`deploy/scripts/docker-deploy-example.sh` 的 Compose 配置检查通过，但两次 `up -d --build` 均在拉取
Docker Hub 的 `curlimages/curl:8.16.0` 或 `alpine:3.22` 时发生 TLS handshake timeout，未进入
业务镜像构建和运行态验收。因此两实例、四实例、10,000 会话容量、Redis/RabbitMQ/数据库故障注入
以及告警接收仍未完成验收；保持 `APP_TIMEOUT_SUBMISSION_V2_ENABLED=false`，不得据此宣称已达成
P99/最大延迟门槛。

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
