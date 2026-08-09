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
