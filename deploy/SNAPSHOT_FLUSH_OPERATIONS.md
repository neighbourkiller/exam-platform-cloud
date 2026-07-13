# 快照增量落库运维说明

## 状态检查

Runtime 使用以下 Redis 结构协调多实例落库：

```text
exam:snapshot-flush:dirty             等待落库
exam:snapshot-flush:processing        已抢占且租约未结束
exam:snapshot-flush:failed            已隔离
exam:snapshot-flush:attempts           失败次数
exam:snapshot-flush:errors             最近错误摘要
exam:snapshot-flush:failed-versions    隔离版本
```

可以使用 Redis CLI 检查积压和隔离原因：

```bash
ZCARD exam:snapshot-flush:dirty
ZCARD exam:snapshot-flush:processing
ZCARD exam:snapshot-flush:failed
ZRANGE exam:snapshot-flush:failed 0 99 WITHSCORES
HGET exam:snapshot-flush:attempts <examId:studentId>
HGET exam:snapshot-flush:errors <examId:studentId>
HGET exam:snapshot-flush:failed-versions <examId:studentId>
```

同时观察 Actuator 指标 `exam.snapshot.flush.events` 和
`exam.snapshot.flush.backlog`。`dirty` 长时间不下降、过期租约持续恢复或
`failed` 大于零都需要处理。

## 隔离快照重放

先修复错误原因，再以当前毫秒时间戳执行下列 Lua。脚本只重放仍处于
`failed` 且 payload 尚未过期的成员，不会读取或输出答案正文：

```bash
EVAL "if redis.call('ZREM',KEYS[1],ARGV[1])==1 and redis.call('EXISTS','exam:snapshot:'..ARGV[1])==1 then redis.call('HDEL',KEYS[2],ARGV[1]); redis.call('HDEL',KEYS[3],ARGV[1]); redis.call('HDEL',KEYS[4],ARGV[1]); redis.call('ZADD',KEYS[5],ARGV[2],ARGV[1]); return 1 end return 0" 5 exam:snapshot-flush:failed exam:snapshot-flush:attempts exam:snapshot-flush:errors exam:snapshot-flush:failed-versions exam:snapshot-flush:dirty <examId:studentId> <currentEpochMillis>
```

返回值必须为 `1`。如果为 `0`，应检查成员是否仍在隔离区以及 payload 是否已经过期。

## 发布与兼容

- 新版本保存快照时会原子写入 dirty 索引；旧版本遗留 key 由每 5 分钟一次的有界 reconciliation 补入。
- 新旧 Runtime 可以短期滚动共存；MySQL `draft_version` 和 Redis lease token 会阻止陈旧任务覆盖新版本。
- 不要人工删除 `processing` 或 token。实例异常后，60 秒租约到期会自动恢复。
- 隔离记录默认保留 7 天；临时数据库故障会持续退避重试，不会因普通次数上限丢弃快照。
