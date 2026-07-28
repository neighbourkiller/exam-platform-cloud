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

正常情况下，快照首次变脏约 15 分钟后到期，Runtime 每 30 秒轮询并单轮处理
最多 10 批。只看 `dirty` 数量不能区分正常等待与异常积压，应同时观察：

- `exam.snapshot.flush.events`：落库、重试、隔离和 Redis 不可用等事件；
- `exam.snapshot.flush.backlog`：`dirty`、`processing`、`failed` 数量；
- `exam.snapshot.flush.oldest.dirty.overdue`：最老 Dirty 超过计划落库时间的毫秒数。

最老 Dirty 逾期超过 60 秒、`failed` 大于零、Processing 长期不回收或
`lease_recovered` 持续增长都需要处理。

## Redis AOF / RDB 状态检查

本地 Compose 使用 `deploy/redis/redis.conf`，开启 AOF `everysec` 并保留 RDB。
AOF 与 RDB 覆盖共享 Redis 实例的全部逻辑数据库。Redis 为便于 Compose
服务互联关闭 protected mode，因此宿主机端口必须保持绑定在 `127.0.0.1:26379`，
不能改成面向局域网或公网的无认证监听。

```bash
docker compose -p exam-platform-cloud -f compose.yaml --env-file .env.microservices \
  exec -T redis redis-cli CONFIG GET appendonly appendfsync save maxmemory-policy
docker compose -p exam-platform-cloud -f compose.yaml --env-file .env.microservices \
  exec -T redis redis-cli INFO persistence
```

下列状态需要告警：

- `aof_last_write_status` 或 `aof_last_bgrewrite_status` 不为 `ok`；
- `rdb_last_bgsave_status` 不为 `ok`；
- `aof_delayed_fsync` 持续增长；
- `/data` 所在磁盘空间不足或 AOF rewrite 长时间未完成。

宿主机还应设置 `vm.overcommit_memory=1`；否则 Redis 会警告后台 RDB 保存或
AOF rewrite 在内存紧张时可能失败。

## 已有数据卷安全开启 AOF

不要删除或重建 `exam-platform-cloud-redis-data`。标准 Compose 启动路径会通过
`deploy/redis/entrypoint.sh` 检查现有数据卷：当卷中存在 `dump.rdb`、但不存在
AOF manifest 或旧版 `appendonly.aof` 时，入口脚本会先用仅开放 Unix Socket 的
临时 Redis 加载 RDB，执行 `CONFIG SET appendonly yes` 并等待 AOF rewrite 成功，
然后才启动正式 Redis。迁移期间 TCP 端口不会开放，依赖服务也不会通过健康检查。

升级前仍应执行 `BGSAVE`，确认 `rdb_bgsave_in_progress:0`、
`rdb_last_bgsave_status:ok`，并将 `/data/dump.rdb` 复制到独立备份位置。之后使用
标准命令重建 Redis；不要绕过入口脚本直接运行 `redis-server`：

```bash
docker compose -p exam-platform-cloud -f compose.yaml --env-file .env.microservices \
  up -d redis
docker compose -p exam-platform-cloud -f compose.yaml --env-file .env.microservices \
  logs redis
```

日志出现“旧 RDB 已成功转换为 AOF”后，再通过 `INFO persistence` 确认
`aof_enabled:1`、`aof_rewrite_in_progress:0` 和 `aof_last_bgrewrite_status:ok`。
已有有效 AOF 的卷会跳过转换；AOF 目录非空但缺少 manifest 时入口脚本会拒绝
启动，避免把不完整的持久化文件当作空库覆盖，此时应先备份数据卷再人工排查。
自动迁移还会在卷内维护 `.exam-rdb-to-aof-migration` 标记；如果容器在 rewrite
期间被中断，下次启动只会清理该次迁移生成的 AOF 产物，并从保留的 RDB 重试。

生产滚动发布时，先升级支持独立 `flush-poll-interval-ms` 的全部 Runtime 实例，
再将 Nacos 的 `flush-interval-ms` 从 30000 调整为 900000。否则旧实例会把
15 分钟延迟误用为调度周期。

回滚时可把 `flush-interval-ms` 恢复为 30000；AOF/RDB 和现有数据卷继续保留。
正常 15 分钟落库只针对草稿：Redis 故障同步兜底和最终交卷写库仍然立即执行。

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
