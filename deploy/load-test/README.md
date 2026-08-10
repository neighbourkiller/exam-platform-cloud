# 10,000 人同刻超时交卷压测

该脚本只测量服务端超时任务，不在截止时主动调用交卷接口。压测前应在隔离环境中准备：

- 2 个 Runtime 实例，并开启 `APP_TIMEOUT_SUBMISSION_V2_ENABLED=true`；
- 10,000 个学生账号及访问令牌；
- 同一场考试、相同 `deadline_time` 的 10,000 个 `ANSWERING` 会话；
- 每个会话已经保存约 100 题、100 KB 的 Redis/MySQL 草稿；
- XXL-JOB 使用 2 秒 CRON 和广播路由。

令牌文件是 JSON 字符串数组，例如 `["token-1","token-2"]`。文件已被 Git 忽略，
不得提交、打印或放入测试报告。

```powershell
$env:BASE_URL='http://localhost:16730/api/v1'
$env:EXAM_ID='10001'
$env:DEADLINE_EPOCH_MS='1786200000000'
$env:TOKENS_FILE='D:\secure\timeout-test-tokens.json'
$env:USERS='10000'
k6 run deploy/load-test/timeout-submission.js
```

脚本阈值要求 P99 小于 30 秒、最大值小于 60 秒、所有会话均完成。压测时还必须
同时采集 Runtime Hikari active/idle/pending、MySQL CPU/IO/Redo/锁等待、Redis 延迟、
任务积压和 Outbox 积压；仅凭 k6 退出码不能证明数据库仍有足够余量。

## 10,000 人考试入场压测

`exam-entry-10000.js` 按每秒 1,000 次、持续 10 秒发送 10,000 个唯一学生的候场请求，
等待服务端稳定槽位后执行激活与试卷交付。测试环境应部署 4 个 Runtime 实例、开启
`APP_EXAM_ENTRY_V2_ENABLED=true`，并提前发布考试等待 Runtime 投影进入 `READY`。

```powershell
$env:BASE_URL='http://localhost:16730/api/v1'
$env:EXAM_ID='10001'
$env:TOKENS_FILE='D:\secure\entry-test-tokens.json'
$env:USERS='10000'
$env:RATE='1000'
$env:DURATION='10s'
k6 run deploy/load-test/exam-entry-10000.js
```

冷缓存场景应先清理该场考试的 Runtime L1/L2 试卷缓存；Redis 故障和 Content 故障场景
通过隔离压测环境的故障注入工具执行，脚本预期新激活失败关闭且不会提前开始个人计时。
双设备冲突可在较小用户数下设置 `$env:DUAL_DEVICE='true'`，验证第二客户端收到
`409 EXAM_CLIENT_CONFLICT`。令牌文件仍不得提交、打印或写入测试报告。
