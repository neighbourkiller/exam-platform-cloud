# 考试答案纠错与重判

## 教师使用

在「考试管理」中打开已结束或已终止考试的「答案纠错与重判」，选择新的客观题答案并填写原因，确认后后台逐份处理。单选、多选、判断、填空沿用原判分规则；不修改题干、选项、分值及已有主观题评分。考试发布时生成的快照始终保留。

任务面板区分重判进度、失败试卷、统计投影进度、等待主观题批阅和题库同步状态。成绩可能升高或降低；处理中不是整场原子切换，统计可能短暂包含多个版本。所有已交卷试卷按新版本计算；扫描后迟到的交卷事件及随后完成的超时交卷同样使用当前有效答案。仅开考、尚未真正提交的 `IN_PROGRESS` 记录不会被提前判分。

普通纠错同步题库中本次修改的题目；题库权限不足、删除或内容冲突不阻止本场重判。有权限的教师或管理员可核对题库现状后重试。题干、题型或选项与快照已不同的题目必须在原题库管理入口单独处理，不能把旧试卷答案强行写回。

「版本历史」可查看答案差异和操作原因，「查看」展示逐份成绩前后变化。恢复版本 0 或其他历史版本会创建新版本并重判，保留当前主观题分数，不修改题库及其他考试。

## 数据与可靠性

- Grading 独占 `grading_exam_key`、`grading_key_version`、`regrade_job`、`regrade_item`、`regrade_bank_sync`。原始答案是版本 0，当前版本独立于原始 PaperSnapshot。
- 每份试卷成绩及客观题明细、重判审计、Outbox 同事务写入；消息保持至少一次投递与幂等消费。没有新增公共队列或更改 Outbox 状态机。
- `grade_revision` 是提交级递增修订号，`answer_version` 是考试答案版本。Reporting 在同一事务中比较版本、更新成绩和题目统计；历史消息缺少版本时按 0 处理。
- 题库同步使用持久化 `operation_id` 和内容指纹。Content 保存结果回执，网络超时重试原标识；返回已经成功的回执不会重新覆盖题库。
- 尚未发出的旧题库同步可被新版本取代。已经发出但结果未知的请求必须先核实；界面会阻止创建后续版本，直到以原标识重试获得确定结果。业务冲突已明确不成功，可继续创建新版本。
- 后台工作在独立执行器运行，不阻塞共享的 Spring 调度线程及 Outbox 发布。每次最多处理 10 个考试任务，每任务每批 100 份，每份独立事务。任务租约 60 秒，每份事务校验令牌并续租，进程崩溃后其他实例可接管；旧令牌不能提交。
- 试卷失败最多自动尝试 5 次，随后任务标记失败，由教师点击重试。题库网络错误间隔 30 秒，达到 5 次后标记失败；手工重试仍使用原 operation_id。审计和未完成任务不自动清理。

## 升级与关闭入口

先阅读 [Outbox 运维说明](OUTBOX_OPERATIONS.md)。本功能不改变该文档规定的 Outbox 发布器兼容边界。

1. 在本次部署使用的配置源中临时设置 Grading 的 `app.regrading.accepting-new-jobs=false`。本属性通过启动配置读取，更改后重启 Grading；只修改仓库文件不会自动更新 Nacos 或运行实例。
2. 备份 Content、Grading、Reporting 数据库，先发布 Reporting（V7 成绩版本迁移及查询接口），再发布 Content（V6 题库回执）、Management（重判上下文）、Runtime（V13 扫描索引及分页接口）。这些是增量兼容变更，必须先让契约提供方就绪。
3. 发布全部 Grading 实例（V8 版本与任务表），确认不存在仍按旧规则首次判分的实例，再发布前端。不得混用旧 Grading 写入方进行重判。
4. 配置 `app.regrading.accepting-new-jobs=true` 后重启 Grading 开放入口。先用专门测试考试完成纠错、统计同步、题库同步和恢复冒烟，再对真实考试使用。
5. 故障时将接受新任务设为 false 并重启；已创建任务继续处理。若必须停止后台执行，同时设置 `app.regrading.worker-enabled=false` 并重启。未完成任务保持持久化，恢复 worker 后继续处理。

上述两个属性代码默认均为 true；轮询属性 `app.regrading.poll-ms` 默认 2000 毫秒。生产环境必须在升级前显式关闭入口，不要把默认值当成已执行的灰度发布。原 `docker-compose.yml` 仍是唯一 Compose 入口；本次没有部署或修改运行中的配置。

已有新版本成绩后，回退旧 Reporting 消费者会丢失版本防回退保护。优先修复后继续运行，或通过历史版本恢复业务结果；不要删除任务、审计记录、版本表或回滚已发布迁移。关闭入口不撤销已经生效的纠错版本。

## 只读排查

在各表所属服务数据库执行，不跨 Schema 查询，不打印凭据或学生原始答案。

Grading 任务及租约：

```sql
SELECT id, exam_id, answer_version, status, total, cursor_id, scan_complete,
       lease_until, last_error, created_at
FROM regrade_job
ORDER BY created_at DESC LIMIT 50;

SELECT job_id, status, COUNT(*) AS item_count, MAX(attempts) AS max_attempts
FROM regrade_item GROUP BY job_id, status;

SELECT status, COUNT(*) AS sync_count, MIN(next_retry_at) AS oldest_retry
FROM regrade_bank_sync GROUP BY status;

SELECT job_id, COUNT(*) AS awaiting_projection
FROM regrade_item
WHERE status='DONE' AND grade_status='GRADED' AND projection_synced=0
GROUP BY job_id;
```

- `RUNNING` 长时间无进度：检查工作器开关、Runtime/Content 可用性和数据库连接；过期租约应在后续轮询接管。不要手改令牌或删除进度记录。
- `FAILED`：在任务面板重试失败试卷；对持续失败核对服务日志中的任务编号、试卷编号和异常类型。日志不输出服务令牌或请求原文。
- 题库 `PENDING/FAILED`：重试原操作核实结果；`FORBIDDEN/CONFLICT/DELETED`：通过核对入口处理，或在题库管理中修正已变化的题目。
- 重判完成但统计仍等待：先检查 Grading 的 Outbox 积压和 FAILED 事件，再检查 Reporting 消费失败、Inbox 和 `/internal/v1/grade-projection/progress` 可用性。Outbox 重放遵循现有运维说明。
- 未完成主观题的试卷不发布最终成绩，主观题完成后再同步投影；不要误报为成绩消息丢失。

## 验收场景

用两名学生分别提交原答案、新答案，验证总分一降一升、及格状态与题目正确率同步变化；重复提交同一请求不创建第二个任务；恢复版本后客观题分数恢复且主观题分数不变。另验证题库无权限、网络超时、失效租约、延迟交卷和乱序成绩消息。自动化测试不能替代部署后的浏览器与 RabbitMQ/Nacos 实际链路冒烟。

## 本次自动化验证（2026-09-12）

使用本机 JDK 21。以下模块级测试命令已执行并通过：

```bash
./mvnw -pl services/exam-grading-service -am test
./mvnw -pl services/exam-content-service,services/exam-management-service,services/exam-runtime-service,services/exam-reporting-service -am test
```

后续改动又执行了定向回归：

```bash
./mvnw -pl services/exam-runtime-service,services/exam-grading-service -am test -Dtest=RegradeMySqlTest,AnswerKeyValidationTest,GradingLeaseMySqlTest,RegradeSubmissionPageMySqlTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl services/exam-grading-service -am test -Dtest=RegradeMySqlTest,GradingLeaseMySqlTest,AnswerKeyValidationTest,GradingOutboxServiceTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl services/exam-management-service,services/exam-content-service -am test -Dtest=TeacherExamViewTest,QuestionCorrectionMySqlTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl services/exam-grading-service,services/exam-reporting-service -am test '-Dtest=RegradeMySqlTest#regradeUpdatesBothDirectionsHandlesDelayedInitialGradingAndRestores,ReportingSubmissionProjectionMySqlTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl services/exam-grading-service -am test '-Dtest=RegradeMySqlTest#unknownBankOutcomeRetainsIdempotencyKeyAndBlocksSupersedingUntilResolved+bankPermissionFailureDoesNotBlockScoresAndOldSyncIsSuperseded' -Dsurefire.failIfNoSpecifiedTests=false
```

重判 MySQL 场景 12 项、原主观题租约 MySQL 场景 6 项、题库纠错 MySQL 场景 3 项、成绩投影 MySQL 场景 5 项均通过；同时验证了答案格式、Runtime 交卷分页和浏览器考试编号精度。

在 `frontend` 执行 `npm run test`（16 项通过）及 `npm run build`（通过，有现有大体积 chunk 提示）；仓库根执行 `git diff --check` 通过。新增文件另检查了尾随空白。

本次使用独立 Testcontainers MySQL，未对运行中的服务执行迁移或部署；尚未执行真实登录浏览器、Nacos、RabbitMQ 到报表页面的整链路验收，必须按上面的部署冒烟场景补验。
