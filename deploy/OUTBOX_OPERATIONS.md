# Outbox 运维说明

## 上线顺序

本次表结构与旧发布器不兼容，六个持有 `outbox_event` 的服务必须协调发布：

1. 停止 IAM、Academic、Content、Management、Runtime、Grading 的旧实例，确保旧发布器不再抢占事件。
2. 对六个服务数据库执行各自的 Flyway 迁移；也可以先以 `OUTBOX_ENABLED=false` 启动一个新实例，只运行迁移而不启动共享发布器。
3. 确认 `lease_token`、`lease_until`、`last_error`、`failed_at` 和三个新索引均已存在。
4. 使用同一版本依次启动新实例，并恢复 `OUTBOX_ENABLED=true`。
5. 检查 `PENDING` 是否持续下降、`SENDING` 是否能在租约期内转为 `PUBLISHED`，以及 `FAILED` 是否异常增长。

不要让旧版本和新版本发布器同时运行；旧版本不了解 `SENDING` 和租约令牌，混跑会破坏状态机语义。

## 状态检查

在对应服务数据库执行：

```sql
SELECT status, COUNT(*) AS event_count, MIN(created_at) AS oldest_event
FROM outbox_event
GROUP BY status;

SELECT id, event_type, aggregate_type, aggregate_id, retry_count,
       last_error, failed_at
FROM outbox_event
WHERE status = 'FAILED'
ORDER BY failed_at DESC
LIMIT 100;
```

`SENDING` 的 `lease_until` 超过当前时间属于正常发布过程；已经过期的租约应在下一次调度后自动转为 `PENDING` 或 `FAILED`。

## 人工重放

确认 RabbitMQ 拓扑和失败原因已修复后，按事件 ID 重置。只允许重置 `FAILED`，避免干扰正在发布的租约：

```sql
UPDATE outbox_event
SET status = 'PENDING',
    retry_count = 0,
    next_retry_time = CURRENT_TIMESTAMP(3),
    lease_token = NULL,
    lease_until = NULL,
    last_error = NULL,
    failed_at = NULL
WHERE id = ?
  AND status = 'FAILED';
```

更新行数必须为 1。共享发布器会在下一轮调度中重新抢占该事件。

## 清理策略

- `PUBLISHED` 默认保留 7 天，然后按每批 500 条、每轮最多 10 批自动删除。
- `PENDING`、`SENDING` 和 `FAILED` 不会自动删除。
- 不要直接把 `SENDING` 改为 `PENDING`；应等待租约恢复逻辑处理，防止旧发布线程覆盖结果。
