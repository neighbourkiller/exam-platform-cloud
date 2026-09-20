# Runtime Service Guidelines

本文件补充 `services/AGENTS.md`，适用于考试开考、答题、心跳、快照、客户端租约、防作弊和交卷相关代码。

## 运行时边界

- 单活答题的权威约束是 Runtime 服务端客户端租约。浏览器锁、页面状态或 `BroadcastChannel` 只改善体验，不能替代服务端校验。
- 修改开考、心跳、保存答案或交卷链路时，必须保持租约令牌和服务端状态校验，不能仅依赖前端阻止冲突。
- 快照使用 Redis 快路径与 MySQL 持久化兜底，并受快照版本和租约保护；不得绕过版本判断直接覆盖较新数据。
- 涉及快照落库、积压、重试或恢复时，先阅读 [`../../deploy/runbooks/SNAPSHOT_FLUSH_OPERATIONS.md`](../../deploy/runbooks/SNAPSHOT_FLUSH_OPERATIONS.md)，并区分 Redis 状态、MySQL 状态和队列状态。

## 调度与可靠性

- 超时交卷由 XXL-Job 触发；不要将其描述或改造成普通 Spring 定时任务，也不要与其他调度器重复触发同一业务作业。
- 可靠性表述必须区分服务端保证、浏览器最佳努力行为和监控信号，不声称绝对零丢失、绝对不重复或 exactly-once。

## 变更检查

- 追踪本次变更影响的入口、共享状态及直接上下游；共享租约、快照版本或交卷状态变化时，扩展检查依赖这些状态的其他入口。
- 修改跨服务 API 或公共事件契约时，遵守 [`../../apis/AGENTS.md`](../../apis/AGENTS.md)；修改 RabbitMQ 拓扑时，遵守 [`../AGENTS.md`](../AGENTS.md) 的消息与契约规则。
- 验证覆盖上述受影响的调用链和状态边界；无法运行的基础设施验证必须明确说明。
