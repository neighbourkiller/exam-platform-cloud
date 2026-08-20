# 万人考试入场上线与回退

## 上线前检查

1. 先部署支持 `SessionStarted` 的 Reporting 消费绑定，再部署 Runtime V9 Flyway、
   新生命周期消费者、三段式入场接口及兼容 `/start`，保持
   `APP_EXAM_ENTRY_V2_ENABLED=false`。消费者必须先于 Runtime 生产者上线，避免新路由键
   在滚动发布窗口出现 `NO_ROUTE`。
2. 确认 RabbitMQ 已声明 `exam.runtime.exam-lifecycle`、`exam.runtime.exam-lifecycle.dlq`
   和独立 DLX `exam.runtime.dlx`，生命周期消费者重试次数为 3。
3. 再部署产生 `ExamPublished` v2 的 Management；Runtime 仍可消费 v1，并通过
   Management `proctoring-context` 补全旧载荷。
4. 对升级前已经发布的考试，以服务令牌调用：

```text
POST /internal/v1/exam-provisioning/{examId}/reconcile
```

返回状态必须为 `READY`，且 `candidateCount` 与 `preparedCount` 相等。该接口受
`SCOPE_internal` 保护，不得通过网关公开给浏览器。
5. 发布 Gateway Reactive Redis 限流配置和新前端；生产 Runtime 基线为 4 个实例、
   每实例 Hikari 最大连接数保持 24。完成万人压测后再开启
   `APP_EXAM_ENTRY_V2_ENABLED=true`。

Nacos 源文件修改后必须通过唯一 Compose 入口强制重建配置发布服务，不能把仓库文件
视为已发布配置：

```bash
docker compose -p exam-platform-cloud -f docker-compose.yml --env-file .env.microservices \
  up --no-deps --force-recreate nacos-config-init
```

## 核对 SQL

以下查询只展示计数，不包含学生身份或密钥：

```sql
select exam_id,provisioning_status,candidate_count,prepared_count,last_error
  from runtime_exam_definition
 where exam_id=?;

select
  count(distinct s.student_id) session_count,
  count(distinct sub.student_id) submission_count,
  count(distinct t.student_id) timeout_task_count
from exam_session s
left join submission sub on sub.exam_id=s.exam_id and sub.student_id=s.student_id
left join submission_timeout_task t on t.session_id=s.id
where s.exam_id=?;
```

三个数量必须与冻结考生人数一致；每个已激活会话应只有一个确定性
`SessionStarted` 对应的 Outbox 事件。

## 重点指标

- `exam.entry.request.duration`：按 `prepare/activate/paper-delivery`、状态和错误码观察。
- `exam.entry.activation.transaction.duration`：只覆盖 READ COMMITTED 短事务。
- `exam.entry.provisioning.*` 与 `exam.entry.lifecycle.dlq`：观察预创建、剩余人数、失败及 DLQ。
- `exam.entry.slot.assigned`：十个槽位的分布偏差不应超过 5%。
- `exam.paper.cache.hit`、`exam.paper.cache.load.duration`、`exam.paper.delivery.payload.bytes`。
- `exam.gateway.entry.rejected`、Hikari active/pending/acquire、Outbox 积压和 MySQL 锁等待。

## 回退边界

一旦 V9 已创建 `PREPARED` 会话，不得回退到不识别该状态的旧 Runtime 二进制。
安全回退方式是关闭新前端路由或设置 `VITE_EXAM_ENTRY_V2_ENABLED=false`，同时保留新版
Runtime 内的 `/start` 兼容适配器。若 Redis 或 Content 故障，新入场保持失败关闭；
不得手工把 `PREPARED` 批量改为 `ANSWERING`，也不得绕过试卷缓存可用性检查启动计时。
