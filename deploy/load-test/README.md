# 10,000 人同刻超时交卷压测

该脚本只测量服务端超时任务，不在截止时主动调用交卷接口。压测前应在隔离环境中准备：

- 先 2 个、再 4 个同版本 Runtime 实例，并仅在隔离环境开启
  `APP_TIMEOUT_SUBMISSION_V2_ENABLED=true`；
- 10,000 个学生账号及访问令牌；
- 同一场考试、相同 `deadline_time` 的 10,000 个 `ANSWERING` 会话；
- 每个会话已经保存约 100 题、100 KB 的 Redis 兼容草稿，并在
  `submission_draft_payload` 保存相同答案的 `GZIP_JSON_V1` 主草稿；
- XXL-JOB 使用 2 秒 CRON 和广播路由。

目录按职责组织：`scenarios/` 保存 k6 场景，`harness/` 保存执行与分析工具，
`compose/` 保存隔离环境覆盖文件，`fixtures/` 保存工具测试夹具，`results/` 保存本地结果。

仓库提供的 `compose/compose.timeout-test.yaml` 只作为 `docker-compose.yml` 的压测覆盖文件使用，
默认项目名为 `exam-platform-cloud-timeout`，并使用独立的 MySQL、Redis、RabbitMQ、MinIO、
JWT 数据卷和 Docker 网络。不要省略该覆盖文件，也不要把压测数据写入主环境命名卷。
执行套件会先用 JDK 21 在宿主机打包当前 Runtime，再通过压测专用 Dockerfile 复制该 JAR；
这样镜像证据仍对应当前源码，同时避免容器内重复构建全仓。

令牌文件是 JSON 字符串数组，例如 `["token-1","token-2"]`。文件已被 Git 忽略，
不得提交、打印或放入测试报告。

```powershell
$env:BASE_URL='http://localhost:16730/api/v1'
$env:EXAM_ID='10001'
$env:DEADLINE_EPOCH_MS='1786200000000'
$env:TOKENS_FILE='D:\secure\timeout-test-tokens.json'
$env:USERS='10000'
k6 run deploy/load-test/scenarios/timeout-submission.js
```

脚本默认阈值要求 P99 小于 30 秒、最大值小于 60 秒、所有会话均完成；经业务方确认采用
45/60 秒口径时，必须显式设置 `DB_P99_GATE_SECONDS=45 DB_MAX_GATE_SECONDS=60`，实际门槛
会写入结果目录的 `metadata.json`，禁止直接修改报告数字。压测时还必须
同时采集 Runtime Hikari active/idle/pending、MySQL CPU/IO/Redo/锁等待、Redis 延迟、
任务积压和 Outbox 积压；仅凭 k6 退出码不能证明数据库仍有足够余量。
执行套件会保存每秒容器 CPU/内存/网络/块 IO 时序及过滤后的 Runtime 超时任务事件，
用于区分数据库锁竞争、HTTP/JWT CPU 竞争和任务预算耗尽；报告不得包含令牌或答案。

两种实例数都要分别执行基线与故障注入：中止一个 Runtime、短时停止 Redis、阻断 MySQL
连接、暂停 RabbitMQ 消费，以及暂停/恢复 XXL-JOB。每次注入只能影响隔离环境，必须记录
注入与恢复时间、租约回收、FAILED/PROCESSING、告警送达和资源曲线，不记录令牌或答案。
最终结论以 `submission_timeout_task.due_at -> completed_at` 的数据库统计为准；RabbitMQ
暂停期间允许 Reporting 延迟，但 Runtime 最终事务仍须满足零遗漏、P99 `<30s`、最大
`<60s`。任一场景不满足时保持 V2 关闭。

状态轮询在截止后首次请求前即开始带抖动的退避（默认基准和上限均为10秒，实际范围
约8～12秒），用于避免 10,000 个 VU 与后台最终化在截止瞬间争抢 Gateway、Runtime 和
MySQL。不得删除这个首次等待来获取更好的“客户端观察延迟”数字。
压测诊断可用 `POLL_BASE_MS`、`POLL_MAX_MS` 显式覆盖轮询窗口；报告元数据会记录实际值。
覆盖值只用于对照实验，不能替代与前端当前策略一致的默认场景放行证据。

## 确定性接管机制测试

`harness/run-timeout-deterministic-takeover.sh` 按固定顺序执行四轮 200 会话机制测试：
领取后杀两轮（`CLAIM_HELD`），随后续租后杀两轮（`RENEW_SUCCEEDED`）。每轮使用
唯一考试 ID、隔离观察附加 JAR 和真实 Runtime 容器；只有首轮构建当前候选镜像，后续
轮次复用同一镜像。脚本会在每轮封存目标任务的初始/恢复领取、令牌指纹、租约、kill、
最终化、采样和数据库终态证据；机制证据失败时停止后续轮次。

```bash
JAVA_HOME=/path/to/jdk-21 \
PATH=/path/to/jdk-21/bin:$PATH \
DB_P99_GATE_SECONDS=45 DB_MAX_GATE_SECONDS=60 \
bash deploy/load-test/harness/run-timeout-deterministic-takeover.sh
```

机制轮的 SLA 结果仍原样写入各轮 `gate-result.json`，但人工阻塞轮不能替代自然负载
容量证明；主环境 `APP_TIMEOUT_SUBMISSION_V2_ENABLED` 不会由该脚本开启。

默认 `LOAD_FLOW=status_only` 验证网络恢复、刷新页面等只查询权威状态的路径；另需执行
`LOAD_FLOW=deadline_submit`，在截止时先按前端协议调用一次 `POST /submit`，仅在服务端
尚未最终化时继续轮询。优化后的前端使用 `LOAD_FLOW=deadline_status_first`：先按默认抖动
等待并查询一次权威状态，只有服务端尚未接管时才发送 `POST /submit`。三种场景必须分开
保存结果，不能用其中一个代替另一个。

## 10,000 人考试入场压测

`scenarios/exam-entry-10000.js` 按每秒 1,000 次、持续 10 秒发送 10,000 个唯一学生的候场请求，
等待服务端稳定槽位后执行激活与试卷交付。测试环境应部署 4 个 Runtime 实例、开启
`APP_EXAM_ENTRY_V2_ENABLED=true`，并提前发布考试等待 Runtime 投影进入 `READY`。

```powershell
$env:BASE_URL='http://localhost:16730/api/v1'
$env:EXAM_ID='10001'
$env:TOKENS_FILE='D:\secure\entry-test-tokens.json'
$env:USERS='10000'
$env:RATE='1000'
$env:DURATION='10s'
k6 run deploy/load-test/scenarios/exam-entry-10000.js
```

冷缓存场景应先清理该场考试的 Runtime L1/L2 试卷缓存；Redis 故障和 Content 故障场景
通过隔离压测环境的故障注入工具执行，脚本预期新激活失败关闭且不会提前开始个人计时。
双设备冲突可在较小用户数下设置 `$env:DUAL_DEVICE='true'`，验证第二客户端收到
`409 EXAM_CLIENT_CONFLICT`。令牌文件仍不得提交、打印或写入测试报告。
