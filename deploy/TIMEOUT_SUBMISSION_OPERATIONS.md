# 超时自动交卷 V2 运维说明

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

1. 暂停 XXL-JOB，并将 `app.timeout-submission.enabled=false` 发布到所有实例；使用环境
   变量占位符时，将 `APP_TIMEOUT_SUBMISSION_V2_ENABLED=false` 注入后滚动重启。
2. 等待已领取任务完成或租约到期，确认没有仍在运行的 V2 工作线程。
3. 只能回退到具备最终答案双读能力的版本；新增表保持不动，不回滚或删除 Flyway。
4. 如需临时恢复旧扫描，确认所有实例均处于关闭 V2 的同一版本后再启用 XXL-JOB。

不要让 V1 固定分片写入和 V2 任务租约写入在不同 Runtime 版本中长期混跑。
