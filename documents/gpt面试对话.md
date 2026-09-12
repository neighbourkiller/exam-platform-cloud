# 面试准备

> 我需要把这个项目写进个人简历：
>
> 1. 给出一份项目简述
> 2. 如果我是后端开发工程师，列出我的项目职责

<details><summary>4 previous messages</summary>

> 我会先依据当前仓库的代码、配置和迁移梳理项目定位与后端亮点，再整理成可直接放进简历的表述；内容会控制在“有技术含量但不过度夸大”的范围内。
>
> <details><summary>Explored a file, a search, a list, ran a command</summary>
>
> <details><summary>Explored a file, a search, a list</summary>
>
> - Searched for `exam-platform-cloud|EkuExam|runtimeFinalized|APP_EXAM_ENTRY_V2_ENABLED|Compose|Nacos|考试` in `MEMORY.md`
> - Read `~/.codex/memories/MEMORY.md`
> - Listed files
>
> </details>
>
> - Ran `sed -n '1,240p' README_zh.md
> sed -n '1,220p' pom.xml
> rg -n '^  (exam-|mysql|redis|rabbitmq|minio|nacos|xxl-job|jwt-key-init|nacos-config-init|app-data-init)|image:|replicas:|APP_RUNTIME_REPLICAS' docker-compose.yml | head -180
> find documents -maxdepth 2 -type f -printf '%p\n' 2>/dev/null | sort | head -80`
>
> </details>
>
> 目前可以确认，这不是简单的 CRUD 考试后台，而是按业务域拆分的 8 个服务，并且在高并发考试运行时、异步判卷、最终一致性、鉴权限流和容器化部署上都有较明确的工程设计。我再核对几条关键实现链路，避免简历职责只堆技术名词。
>
> <details><summary>Explored 5 files, 4 searches</summary>
>
> - Searched for `class .*Outbox|inbox_event|outbox_event|RabbitTemplate|PublisherConfirm|@RabbitListener` in `platform`
> - Searched for `snapshot|Snapshot|lease|Lease|timeout|Timeout|SKIP LOCKED|Caffeine|Cache<|RateLimiter|token bucket|RedisScript|DefaultRedisScript` in `exam-runtime-service`
> - Searched for `@PreAuthorize|Jwt|refresh token|RefreshToken|SERVICE_CLIENT|service.client|MinIO|XxlJob|@XxlJob` in `services`
> - Searched for `^#|^##|^###` in `微服务系统组件选型与Java后端面试回答.md`
> - Read `./documents/微服务系统组件选型与Java后端面试回答.md`
> - Read `./services/exam-runtime-service/AGENTS.md`
> - Read `~/.codex/memories/MEMORY.md`
> - Read `./README_zh.md`
> - Read `./pom.xml`
>
> </details>
</details>

以下内容可直接用于后端开发工程师简历。职责建议只保留你实际参与过、面试时能够展开说明的条目。

### 1. 项目简述

**EkuExam Cloud 微服务在线考试平台**

基于 Java 21、Spring Boot、Spring Cloud 构建的在线考试与成绩评定平台，面向管理员、教师和学生三类用户。系统按身份认证、教务管理、题库试卷、考试管理、考试运行时、阅卷评分和统计报表等业务域拆分微服务，覆盖组卷、发布、开考、答题快照、防作弊、交卷、自动判分及成绩分析等完整考试流程。采用 Nacos、OpenFeign、RabbitMQ、Redis、MySQL、MinIO 和 XXL-Job，实现服务治理、高并发考试状态管理、异步事件处理与容器化部署。

**核心技术栈：** Java 21、Spring Boot 4、Spring Cloud、Spring Security、MyBatis-Plus、MySQL、Redis、RabbitMQ、Nacos、OpenFeign、Flyway、MinIO、XXL-Job、Docker Compose。

### 2. 后端开发工程师项目职责

- 参与在线考试平台微服务架构设计，按照业务领域拆分 IAM、教务、题库、考试管理、考试运行时、阅卷和报表服务，维护服务间 DTO 与 OpenFeign 接口契约。
- 负责考试核心业务开发，完成考试发布、考生准入、开始考试、答题保存、心跳续租、手工交卷及超时自动交卷等业务流程。
- 设计 Redis 答题快照机制，通过版本控制、客户端租约、延迟刷盘和 MySQL 降级持久化，降低高频答题操作对数据库的压力，并避免多端答题和旧数据覆盖。
- 使用 Caffeine 与 Redis 构建试卷数据两级缓存，结合缓存预热和并发控制优化集中开考场景下的试卷读取性能。
- 基于 RabbitMQ 和 Outbox/Inbox 模式实现考试发布、交卷、判分及报表更新等异步事件链路，通过发布重试、消费幂等和死信队列保证最终一致性。
- 负责客观题自动判分、主观题批阅任务及成绩汇总流程，将交卷主链路与阅卷处理解耦，提升核心考试流程的可用性。
- 基于 Spring Security 和 RSA JWT 实现统一认证授权、刷新令牌轮换、服务间身份认证以及角色级接口权限控制。
- 使用 Redis Lua 脚本实现登录及考试入场接口的分布式限流，保证多实例部署下限流判断的原子性。
- 负责各微服务独立 MySQL Schema 的设计与维护，使用 Flyway 管理数据库版本，通过唯一约束、复合索引和租约字段优化幂等消费及批量任务领取。
- 使用 XXL-Job 实现超时交卷等分布式任务调度，通过分片、任务租约和失败重试避免多实例重复执行。
- 参与 Docker Compose 部署体系建设，编排 MySQL、Redis、RabbitMQ、Nacos、MinIO、XXL-Job及各业务服务，实现配置发布、密钥初始化和演示数据导入。
- 编写 JUnit、Mockito 和 Testcontainers 测试，覆盖核心业务、消息幂等、并发任务领取及数据库事务边界。

如果简历篇幅有限，建议优先保留“考试运行时、Redis 快照、Outbox/Inbox、鉴权限流、数据库优化、容器化部署”这六类职责，它们比普通 CRUD 更能体现后端能力。以上表述依据当前 [README_zh.md](./README_zh.md:19)、[pom.xml](./pom.xml:18)及服务实现静态核对整理；本次未修改代码，也未运行测试或部署验证。

> 面向管理员、教师和学生的前后端分离在线考试平台，覆盖题库管理、智能组卷、考试发布、在线作答、自动交卷、异步阅卷、成绩分析及监考风险研判等完整流程。围绕高并发作答场景，重点解决考试状态流转、断线恢复、超时交卷、异步评分与监考事件追踪等问题。
>
> - 设计考试与作答会话状态机，基于 **MySQL** 唯一约束保证每位学生每场考试仅创建一条会话和提交记录；结合客户端租约与心跳续租机制，限制多窗口同时作答，并校验考试时间窗、会话状态和提交状态。
> - 设计 **Redis** 版本化答题快照：前端本地草稿与服务端快照协同，支持刷新、断网恢复；快照定期落库并以 MySQL 作为兜底，自动交卷时读取最新有效快照生成最终答案，降低网络波动导致的答案丢失风险。
> - 通过 **XXL-JOB** 分片扫描超时作答会话，使用条件更新抢占处理权，避免多实例重复自动交卷；自动交卷完成后统一更新会话、提交记录和最终答案，并投递交卷事件。
> - 基于 **RabbitMQ** 解耦交卷与评分：运行时服务通过 Transactional Outbox 可靠写入 SubmissionAccepted 事件，评分服务以 Inbox 去重消费，并配置有限重试与死信队列，提高消息重复投递和短暂故障下的处理可靠性。
> - 实现客观题自动评分与主观题人工阅卷流程，支持按题型判分、按题目聚合批阅、批量评分、成绩重算和最终成绩生成。
> - 建设监考风险分析能力，采集切屏、标签页隐藏、退出全屏、复制粘贴和网络离线等事件；按事件频次与离屏时长计算风险分级，为教师提供考生排序、事件时间线和人工处置入口。
>
> 这是我在之前在简历中对本项目的简述和亮点阐述，是否需要根据上述回答修改内容？应该怎么修改？

<details><summary>4 previous messages</summary>

> 需要做一次“去掉可能被追问穿、补上当前实现亮点”的校准。我会逐条核对会话唯一约束、快照恢复、超时交卷、Outbox/Inbox、阅卷和监考风险计算，最后给你一版可直接替换的简历文本。
>
> <details><summary>Explored 9 files, 7 searches</summary>
>
> - Searched for `exam-platform-cloud|风险|监考|proctor|snapshot|超时交卷|SubmissionAccepted|unique.*session|会话` in `MEMORY.md`
> - Searched for `CREATE TABLE IF NOT EXISTS exam_session|CREATE TABLE.*submission|UNIQUE KEY|UNIQUE INDEX|unique` in `migration`
> - Searched for `class .*Lease|leaseToken|heartbeat|renew|draftVersion|snapshotVersion|saveSnapshot|flush|fallback` in `java`
> - Searched for `SubmissionAccepted|insert ignore into inbox_event|@RabbitListener|dead-letter|retry` in `exam-runtime-service`
> - Searched for `risk|Risk|风险|TAB_HIDDEN|FULLSCREEN|COPY|PASTE|OFFLINE|offline|visibility|proctor` in `exam-runtime-service`
> - Searched for `objective|subjective|regrade|recalculate|batch|score|manual` in `java`
> - Read `./services/exam-runtime-service/src/main/resources/db/migration/V1__runtime_schema.sql`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/job/ExamTimeoutSubmitJob.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/repository/TimeoutTaskRepository.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/ProctoringService.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionService.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionCoordinator.java`
> - Searched for `localStorage|examDraft|draft|snapshot|offline|restore|恢复` in `student`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/ExamSnapshotService.java`
> - Read `./services/exam-grading-service/src/main/java/com/ekusys/exam/grading/messaging/SubmissionAcceptedConsumer.java`
> - Read `./services/exam-grading-service/src/main/java/com/ekusys/exam/grading/messaging/GradingRabbitConfig.java`
>
> </details>
>
> 已核对出一个需要重点修改的地方：当前 V2 超时交卷实现虽然由 XXL-Job 读取了分片参数，但进入新协调器后并未按分片参数过滤任务，因此简历里直接写“XXL-JOB 分片扫描”会比代码实际保证更强。更稳妥的亮点应写成“批量扫描 + `FOR UPDATE SKIP LOCKED` + 任务租约”。其余几条整体方向是准确的。
>
> <details><summary>Explored 8 files, 3 searches</summary>
>
> - Searched for `timeout-submission|enabled:|APP_TIMEOUT|APP_EXAM_ENTRY` in `application.yml`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/job/ExamTimeoutSubmitJob.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionService.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionCoordinator.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/ProctoringService.java`
> - Searched for `enum .*Status|ExamSessionStatus|AUTO_SUBMITTING|ANSWERING|SUBMITTED|IN_PROGRESS|PROCESSING` in `java`
> - Searched for `app.snapshot|flush-interval|flush-poll` in `exam-runtime-service.yml`
> - Read `./services/exam-runtime-service/src/main/resources/db/migration/V1__runtime_schema.sql`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/repository/TimeoutTaskRepository.java`
> - Read `./services/exam-grading-service/src/main/java/com/ekusys/exam/grading/messaging/SubmissionAcceptedConsumer.java`
> - Read `~/.codex/memories/MEMORY.md`
>
> </details>
</details>

需要修改，但不建议推倒重写。你原来的版本比上一版更具体、更有技术深度，适合中高级 Java 后端简历。主要需要收紧几处容易被面试官追问的表述。

### 建议修改点

- “每位学生仅创建一条”改成“通过唯一约束避免重复创建”。唯一约束保证的是“至多一条”，不是必然创建。
- “状态机”改成“状态流转与条件更新”。当前实现主要依靠状态字段、事务和带状态条件的 SQL，没有集中式状态机框架。
- “限制多窗口同时作答”改成“服务端单活客户端租约”。浏览器侧锁只能改善体验，服务端租约才是权威约束。
- “XXL-JOB 分片扫描”建议删除“分片”。旧流程确实使用分片参数，但新版任务协调器主要依靠批量领取、`FOR UPDATE SKIP LOCKED` 和任务租约保证多实例并发安全。
- RabbitMQ 不要笼统写“可靠消息”或暗示 Exactly Once，建议明确写成“至少一次投递、幂等消费、最终一致性”。
- “风险研判”略显智能化，当前实现属于基于规则的风险计分，改成“监考风险分级”更准确。

## 推荐的最终简历版本

**EkuExam Cloud 微服务在线考试平台**

面向管理员、教师和学生的前后端分离在线考试平台，基于 Spring Cloud 按身份认证、教务、题库试卷、考试管理、考试运行时、阅卷及报表等业务域拆分服务，覆盖题库管理、智能组卷、考试发布、在线作答、自动交卷、异步阅卷、成绩分析和监考风险分级等完整流程。面向集中开考和高频答题保存场景，重点解决考试状态一致性、断线恢复、超时交卷、异步评分及监考事件追踪等问题。

**技术栈：** Java 21、Spring Boot 4、Spring Cloud、Spring Security、MyBatis-Plus、MySQL、Redis、RabbitMQ、Nacos、OpenFeign、Flyway、XXL-Job、MinIO、Docker Compose。

- 围绕考试会话、提交记录和超时任务设计状态流转，通过带状态条件的 SQL 控制合法迁移，并利用 MySQL `(exam_id, student_id)` 唯一约束避免重复创建会话和提交记录；基于服务端客户端租约及心跳续租实现单活作答控制，校验考试时间窗、会话状态与租约令牌。
- 设计 Redis 版本化答题快照机制，协同前端 IndexedDB 本地草稿实现刷新及断网恢复；通过单调版本号防止旧快照覆盖新答案，采用延迟刷盘将快照增量持久化至 MySQL，并在 Redis 异常时降级写库，降低网络波动和缓存故障造成的答案丢失风险。
- 使用 XXL-Job 调度超时交卷任务，基于任务表、`FOR UPDATE SKIP LOCKED`、处理租约及失败退避实现多实例并发领取与故障恢复；在本地事务中统一写入最终答案、更新会话及提交状态，并生成交卷事件。
- 基于 RabbitMQ 解耦交卷与评分链路，将业务状态与 `SubmissionAccepted` 事件通过 Transactional Outbox 在同一事务落库，结合发布确认、失败重试及死信队列完成事件投递；评分服务使用 Inbox 和唯一约束进行消费去重，实现“至少一次投递、幂等消费、最终一致性”。
- 实现客观题自动判分与主观题人工阅卷流程，支持不同题型答案比对、按题目聚合待批任务、批量评分、成绩重新汇总以及最终成绩事件生成。
- 建设基于规则的监考风险分析能力，采集窗口失焦、标签页隐藏、退出全屏、复制粘贴及网络离线等事件，结合事件频次和累计离屏时长计算风险分数与等级，为教师提供风险排序、事件时间线和人工确认、误报及关闭等处置能力。

### 为什么这版更适合简历

这版保留了你原文最有价值的六条主线：

1. 状态与幂等；
2. Redis 快照和容灾；
3. 多实例超时交卷；
4. 可靠事件与最终一致性；
5. 异步阅卷；
6. 监考风险分析。

同时避免了三个常见风险：把“唯一约束”说成绝对创建保证、把消息系统说成 Exactly Once、把规则计分包装成智能风控。

如果版面只能容纳四条，建议保留前四条。阅卷和监考可以压缩进项目简述，因为前四条最能体现 Java 后端工程深度。不要添加“支撑万人同时在线”“QPS 提升百分之多少”等数字，除非你确实做过压测并能拿出测试环境、数据规模和结果。

以上结论来自当前代码和迁移的静态核对；未将测试环境能力夸大为生产运行成果。

> 针对修改后的简历的项目经历，使用 [$interview-master](./.agents/skills/interview-master-skill-main/SKILL.md) 帮助我准备业务面试问题，目前还没有确定面试哪家公司，但是岗位可以认定为 java后端开发

<details><summary>2 previous messages</summary>

> 我会使用 `interview-master`，按技术/研发岗位的业务面流程准备；这个 Skill 要求先完成岗位识别和真实经历盘点，所以我会先读取 Java 后端对应的考察维度与答案模板，再用少量问题确认你的目标职级和薄弱点，而不是直接丢一套通用题库。
>
> <details><summary>Explored 7 files</summary>
>
> - Read `./.agents/skills/interview-master-skill-main/SKILL.md`
> - Read `./.agents/skills/interview-master-skill-main/SKILL.md`
> - Read `./.agents/skills/interview-master-skill-main/SKILL.md`
> - Read `./.agents/skills/interview-master-skill-main/references/role_technical.md`
> - Read `./.agents/skills/interview-master-skill-main/references/competency_answer_template.md`
> - Read `./.agents/skills/interview-master-skill-main/references/interview_advanced_guide.md`
> - Read `./.agents/skills/interview-master-skill-main/references/behavioral_question_bank.md`
>
> </details>
</details>

已确定方向：以 **Java 后端开发的一面/二面业务技术面** 为目标，围绕该考试项目做项目深挖、架构取舍、故障场景和压力追问。因为尚未确定公司，暂不搜索公司面经；确定目标后再补充真题和公司业务迁移题。

在正式出题前，需要你回答两个关键问题：

1. 你的目标层级和实际经验是多少：校招、0–2 年、3–5 年，还是 5 年以上？
2. 简历中的六项——会话租约、Redis 快照、超时交卷、Outbox/Inbox、阅卷、监考风控——哪些是你亲自设计并实现的？你最担心被追问哪一项？

你回答后，我会依次产出：

- Java 后端能力—项目经历匹配矩阵
- 15–20 道高概率业务面试题
- 最危险的简历追问及暴露点
- 选出 3–5 道核心题，一题一题训练
- 每题由你先回答，我再做面试官式诊断、答案重构和连续追问

不会直接生成一套背诵稿，因为这份简历技术密度较高，面试官很可能沿着事务边界、并发竞争、故障恢复和方案取舍连续追问；没有先确认真实贡献边界，模板答案很容易被问穿。

> 1.校招 2.最担心被问到超时交卷

<details><summary>2 previous messages</summary>

> 继续按 `interview-master` 的阶段三推进。校招不会只看你能否复述链路，面试官会把“超时交卷”当入口，连续验证 MySQL 锁、事务、幂等、Redis 快照、RabbitMQ 一致性和故障恢复；我会先建立题目梯度，再从第一道核心题开始让你实答。
>
> <details><summary>Explored a search</summary>
>
> - Searched for `exam-platform-cloud exam entry|超时交卷|Timeout chain|Runtime client lease|简历|Java 后端` in `MEMORY.md`
>
> </details>
</details>

## 我的判断

“超时交卷”是这份校招简历里最强、也最危险的亮点。

答好后，它能同时证明你具备业务建模、数据库并发、事务一致性、Redis、消息队列和故障恢复能力；答不好，面试官会判断你只是记住了 `XXL-Job + RabbitMQ` 这些名词。

由于暂无目标公司，下面的问题属于“基于项目代码和 Java 校招面试规律的预测题”，暂不属于公司真题。

## 能力—经历匹配矩阵

这里评估的是目前简历呈现出的匹配度，不代表你的真实掌握程度。

| 能力域 | 项目证据 | 当前匹配度 | 风险 |
|---|---|---:|---|
| 业务建模 | 会话、提交、超时任务状态流转 | ★★★★☆ | 容易只讲技术、不讲业务规则 |
| MySQL并发与事务 | 唯一约束、条件更新、行锁、`SKIP LOCKED` | ★★★☆☆ | 校招面试官会追问锁和隔离级别 |
| 分布式任务可靠性 | XXL-Job、任务租约、失败退避、补偿 | ★★★☆☆ | 最容易被故障场景问倒 |
| 数据恢复 | Redis版本化快照、MySQL兜底、最终答案固化 | ★★★★☆ | 必须讲清“哪个快照最新” |
| 消息一致性 | Outbox、Inbox、重试、DLQ | ★★★★☆ | 不能说成Exactly Once |
| 工程验证 | 测试、监控、容量和线上结果 | ★★☆☆☆ | 暂时缺少实际规模和结果数据 |

## 高概率业务面试题

### 1. 超时交卷业务链路

1. 请完整介绍从考试到期到评分完成的业务链路。
2. 为什么不能让前端倒计时结束后直接调用普通交卷接口？
3. 为什么要单独设计 `submission_timeout_task`，而不是定时扫描 `exam_session`？

### 2. 并发与幂等

4. 多个 Runtime 实例同时扫描到同一场考试，如何防止重复交卷？
5. 手工交卷和自动交卷同时发生，会出现什么竞争？谁应该成功？
6. `(exam_id, student_id)` 唯一约束、条件更新和任务租约分别解决什么问题？

### 3. MySQL锁与事务

7. `SELECT ... FOR UPDATE SKIP LOCKED` 的作用是什么？不用它会怎样？
8. 超时交卷事务中需要更新哪些数据？为什么必须放在同一事务？
9. 如果事务执行到一半进程崩溃，任务如何恢复？
10. 为什么租约过期后还要再次校验 `claimToken`，不能直接提交？

### 4. 快照与最终答案

11. 自动交卷怎样判断 Redis 快照和 MySQL 草稿哪个更新？
12. Redis 宕机时还能不能自动交卷？最终答案从哪里获取？
13. 为什么最终答案需要单独固化，不能评分时直接读取当前快照？

### 5. 消息与故障恢复

14. 数据库提交成功但 RabbitMQ 不可用，如何保证评分事件之后还能发送？
15. RabbitMQ 重复投递时，为什么不会重复创建评分记录？
16. 消费失败重试多次仍不成功怎么办？DLQ中的消息如何处理？
17. Outbox/Inbox是否能保证消息绝对不丢、绝对不重复？

### 6. 容量与方案取舍

18. 如果十万名考生在同一分钟结束考试，当前方案的瓶颈在哪里？
19. 为什么选RabbitMQ，而不是同步调用评分服务？
20. 为什么选择数据库任务租约，而不是Redis分布式锁？
21. 如果重新设计，你认为当前超时交卷方案还需要改进什么？

## 当前最危险的追问

面试前必须准备清楚：

- 你本人具体负责了超时交卷的哪部分，是设计、编码、测试，还是分析优化。
- 项目是否进行过真实压测；没有就明确说没有，不能虚构并发量。
- 手工交卷与自动交卷的竞争结果及事务锁顺序。
- 任务领取成功后、事务提交前宕机如何恢复。
- Redis和MySQL都有草稿时，如何通过版本号选择最新数据。
- Outbox只能实现“至少一次投递＋幂等消费＋最终一致性”，不能回答Exactly Once。
- 不要主动说“新版通过XXL-Job分片扫描”；当前更适合强调批量领取、行锁和任务租约。

## 第一题：现在开始实答

> 请用2～3分钟介绍这个项目的超时自动交卷完整流程，并说明为什么没有采用“定时任务直接扫描超时会话并提交”的简单方案。

回答时按这个顺序：

1. 业务问题；
2. 任务如何产生和触发；
3. 多实例如何领取；
4. 如何生成最终答案；
5. 一个事务里更新什么；
6. 如何触发评分；
7. 失败后怎么恢复；
8. 为什么这样设计。

请直接像真实面试一样回答，不用追求完美，也先不要查标准答案。下一轮我会从面试官视角指出漏洞，并围绕你的回答连续追问。

> 超时交卷流程是 在考试结束时，XXL-Job扫描超时的考试会话，通知考试服务交卷，多个考试服务实例通过数据库悲观锁抢占任务，并通过状态机保证幂等性，各实例从Redis中获取最新版本答案后提交，并写入outbox事件，后续考试服务的定时器扫描outbox表的的记录进行后续的评分操作

<details><summary>2 previous messages</summary>

> 继续使用 `interview-master` 做逐题训练。你已经把主链路说出来了，但目前这版如果在真实面试中回答，会被连续追问穿：任务扫描对象、锁的作用、幂等来源、快照选择以及 Outbox 后续链路都有不准确或过度概括之处。我先按当前实现核对关键参数和调用边界，再给你可口述的修正版。
>
> <details><summary>Explored 9 files, 3 searches</summary>
>
> - Searched for `Timeout chain|HANDOFF_ACCEPTED|Runtime client lease|Outbox|Inbox|至少一次` in `MEMORY.md`
> - Searched for `batch-size|worker-count|lease-ms|max-run-ms|max-attempts` in `exam-runtime-service.yml`
> - Searched for `@Scheduled|publishPending|OutboxPublisher|SubmissionAcceptedConsumer|processSubmission` in `exam-outbox-support`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionCoordinator.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/ExamSnapshotService.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionCoordinator.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/repository/TimeoutTaskRepository.java`
> - Read `./platform/exam-outbox-support/src/main/java/com/ekusys/exam/common/outbox/OutboxPublisher.java`
> - Read `./services/exam-grading-service/src/main/java/com/ekusys/exam/grading/messaging/SubmissionAcceptedConsumer.java`
> - Read `./deploy/nacos-config/exam-runtime-service.yml`
> - Read `~/.codex/memories/MEMORY.md`
> - Read `~/.codex/memories/MEMORY.md`
>
> </details>
</details>

## 面试官评价：4/10

你说出了主干，但目前更像“技术名词串联”，还没有讲清楚并发控制、事务边界和故障恢复。真实面试中，下面几处会被立即追问。

### 主要问题

1. **“考试结束时”不准确**
   应该是某个考生的服务端会话到达 `deadline_time`，不一定等到整场考试结束。

2. **“扫描超时会话”不够准确**
   强化后的流程扫描的是 `submission_timeout_task` 任务表，包括到期的待处理任务和租约过期的处理中任务。

3. **“通知考试服务交卷”不准确**
   XXL-Job执行器就在Runtime服务中，Job Handler直接调用超时交卷业务逻辑，不是向另一个考试服务发通知。

4. **“悲观锁抢占任务”只说了一半**
   行锁只在短事务中完成批量领取；耗时处理阶段依靠 `claimToken + leaseUntil + 定期续租` 维持任务所有权。

5. **“状态机保证幂等”过于笼统**
   幂等实际上由唯一约束、状态条件更新、任务令牌、租约校验、Outbox事件ID和Inbox去重共同保证。

6. **快照来源说错了一半**
   不是无条件读取Redis，而是比较Redis快照版本和MySQL持久化草稿版本，选择更新的一份；Redis异常时使用MySQL兜底。

7. **Outbox后续链路说错了**
   Runtime的Outbox定时发布器只负责将事件发送至RabbitMQ；真正评分的是Grading服务的RabbitMQ消费者，不是“考试服务定时器扫描Outbox后评分”。

8. **没有回答“为什么这样设计”**
   面试官不只想听执行步骤，还要听任务表、短事务、租约和Outbox分别解决了什么问题。

## 可直接练习的完整回答

建议口述时间约2分钟：

> 超时交卷的核心目标，是当某个考生的服务端答题会话到达截止时间后，由服务端可靠接管交卷，保证不重复提交、不遗漏提交，并使用最新有效答案完成评分，而不能依赖前端倒计时。因为浏览器可能断网、关闭，客户端时间也可能被修改。
>
> 在考生进入答题状态时，系统会为会话准备一条超时交卷任务，记录会话ID、截止时间和处理状态。到期后，由XXL-Job触发Runtime服务中的超时交卷处理器，批量查询到期任务以及租约已经失效的处理中任务。
>
> 多个Runtime实例并发执行时，系统先在一个短事务中使用`FOR UPDATE SKIP LOCKED`领取任务。`SKIP LOCKED`可以让不同实例跳过已经被其他实例锁定的任务，避免相互等待。领取成功后，把任务更新为`PROCESSING`，写入唯一的`claimToken`和`leaseUntil`，并将会话从`ANSWERING`条件更新为`AUTO_SUBMITTING`。当前默认参数中，每批领取8条任务，任务租约为60秒，并每15秒续租一次。
>
> 行锁不会一直持有到交卷完成，因为读取快照和生成最终答案可能比较耗时。后续处理依靠任务租约维持所有权。处理器先读取MySQL中的持久化草稿版本，再读取Redis快照并比较版本号，选择更新的一份；如果Redis不可用，则直接使用MySQL草稿。随后把答案编码成不可变的最终提交载荷。
>
> 最终提交放在一个本地事务中完成。事务按照任务、会话、提交记录的顺序加锁，再次校验`claimToken`和租约是否有效，然后保存最终答案，将提交记录从`IN_PROGRESS`更新为`PROCESSING`，写入`SubmissionAccepted` Outbox事件，将会话更新为`SUBMITTED`，最后把超时任务标记为`DONE`。如果任何一步失败，整个事务回滚。
>
> 事务提交后，Runtime的Outbox发布器会扫描待发送事件，通过Publisher Confirm发送到RabbitMQ。Grading服务消费`SubmissionAccepted`事件，并通过Inbox事件ID去重，然后执行客观题评分和主观题任务创建。因此这里实现的是至少一次投递、幂等消费和最终一致性，而不是Exactly Once。
>
> 如果处理实例中途宕机，任务租约到期后可以由其他实例重新领取；短暂异常会按照退避策略重试。相比直接扫描会话表并在一个长事务中完成所有操作，独立任务表可以保存重试次数、下次执行时间、租约和失败原因，缩短锁持有时间，也更方便故障恢复和监控。

相关实现集中在 [TimeoutSubmissionCoordinator.java](./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionCoordinator.java:182)、[ExamSnapshotService.java](./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/ExamSnapshotService.java:74) 和 [OutboxPublisher.java](./platform/exam-outbox-support/src/main/java/com/ekusys/exam/common/outbox/OutboxPublisher.java:45)。

## 高概率追问

### 追问一：有了数据库悲观锁，为什么还需要任务租约？

考察你是否理解锁的生命周期。

参考回答：

> 悲观锁只保护领取任务的短事务，事务提交后行锁就释放了。真正的答案读取和最终提交在事务外继续执行，因此需要任务租约表示当前处理者。否则行锁释放后，其他实例可能再次领取同一任务。任务租约还能在实例宕机后自动过期，让其他实例接管。

### 追问二：旧实例租约过期后仍然继续执行怎么办？

> 最终事务会重新锁定任务，并校验任务状态、`claimToken`和`leaseUntil`。即使旧实例仍在运行，只要租约已失效或任务已被其他实例重新领取，它就不能完成最终提交。

### 追问三：Redis快照和MySQL草稿不一致怎么办？

> 不能默认相信Redis。系统读取MySQL中的持久化版本，再与Redis快照中的`snapshotVersion`比较，使用版本更新的一份。如果Redis数据损坏、身份不匹配或连接失败，则退回MySQL草稿。

### 追问四：数据库提交成功，但RabbitMQ宕机怎么办？

> 业务数据和Outbox事件在同一个本地事务中提交，因此RabbitMQ故障不会导致事件信息丢失。Outbox发布器后续会重新领取并发送，收到RabbitMQ确认后才标记为已发布。代价是消息可能重复，所以消费端必须使用Inbox去重。

### 追问五：手工交卷和自动交卷同时发生怎么办？

> 两条链路必须遵循一致的加锁顺序和条件状态迁移。只有持有合法状态的一方能够把会话从`ANSWERING`推进到提交接管状态，并把提交记录从`IN_PROGRESS`推进到`PROCESSING`；失败方读取最新状态后返回“正在提交”或“已经提交”，不能再次生成最终提交。

## 遗漏项审查

总体风险：🔴 高风险，主要不是方案本身，而是你的表达还不足以证明你真正掌握了它。

- 🔴 **角色边界不明**：你必须能说明自己负责的是方案设计、编码、测试还是问题分析。
- 🔴 **运行状态风险**：当前Compose中超时交卷V2默认开关仍为关闭状态，不能说它已经在生产环境稳定运行。
- 🔴 **缺少验证结果**：没有真实压测数据时，不要声称支持十万考生或给出虚构QPS。
- 🟡 **术语风险**：少说“状态机自动保证幂等”，改为逐项说明状态条件更新、唯一约束和任务令牌。
- 🟡 **结果表达不足**：可以说解决了重复处理、宕机接管和消息解耦，但不要编造业务指标。

## 第二次作答

现在请不要照抄上面的答案，用你自己的语言重新回答一次，控制在90～120秒。

这次必须讲清楚四件事：

1. 为什么不能依赖前端自动交卷；
2. 数据库行锁和任务租约的区别；
3. Redis与MySQL如何选择最新答案；
4. Outbox之后是谁执行评分。

> 超时交卷流程：学生进入考试时，服务端为每一个学生生成了一条任务记录，记录了考试结束时间；XXL-job扫描任务表记录中结束时间小于当前时间记录，然后分片广播到考试运行时实例，这里不依靠前端自动交卷是因为前端用户浏览器时间可能与服务端不匹配，前端可能断网；各实例使用短事务 For update skip locked领取任务，防止阻塞，然后对抢占到的任务生成租约，用于后续对其他表的操作，（因为前面获取到的数据库行所只是任务表的锁，操作完就释放了，需要使用租约来抢占后续对其他表的业务）；接下来各实例从MySQL中获取最新的答案，如果Redis的答案版本比较新并且校验没有问题就使用Redis的，将答案写进答案表后，修改任务、会话的状态，在outbox表写入事件；考试实例的outbox发布器扫描待发布的事件，使用publish confirm发送到消息队列中，后续阅卷服务接收消息，使用inbox去重消费消息

<details><summary>4 previous messages</summary>

> 继续按 `interview-master` 做第二轮复盘。这次明显更接近合格答案：你已经讲清了前端不可信、短事务、行锁与租约区别、Redis/MySQL版本选择，以及Outbox到阅卷服务的边界。我会重点检查仍可能被抓住的两个点——任务产生时机和XXL-Job的“分片广播”表述。
>
> <details><summary>Explored 5 files, 3 searches</summary>
>
> - Searched for `submission_timeout_task|createTimeout|timeoutTask|ensure.*Task|insert into submission_timeout_task|repairTask` in `java`
> - Searched for `processShard|shardIndex|shardTotal|processDue\(` in `java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/job/ExamTimeoutSubmitJob.java`
> - Searched for `Timeout chain|分片|processDue|超时交卷` in `MEMORY.md`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/entry/ExamProvisioningService.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/entry/ExamActivationTransactionService.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/ExamRuntimeService.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionService.java`
>
> </details>
>
> 第二次回答已经达到“能继续深挖”的水平，但还有三处必须修正，否则熟悉代码的面试官会抓住：V2任务不是简单在学生进入时新建、V2没有真正使用XXL-Job分片过滤、最终答案也不是直接写入普通答案表。我再核对最终载荷表后，给你定稿和下一道压力题。
>
> <details><summary>Explored 3 files, a search</summary>
>
> - Searched for `submission_final_payload|GZIP_JSON|payload_sha|sha256|finalPayload` in `migration`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/SubmissionFinalPayloadService.java`
> - Read `./services/exam-runtime-service/src/main/resources/db/migration/V8__timeout_submission_v2.sql`
> - Read `./services/exam-runtime-service/src/main/resources/db/migration/V11__harden_timeout_submission_finalization.sql`
>
> </details>
</details>

## 第二轮评价：7/10

这次已经具备清晰的主链路，尤其是以下四点说对了：

- 解释了为什么不能依赖前端触发；
- 区分了短事务行锁与跨事务任务租约；
- 知道需要比较Redis和MySQL的答案版本；
- 正确讲出了Outbox → RabbitMQ → Grading → Inbox。

从4分提升到7分。剩余问题主要是三个实现细节。

## 还需要纠正的地方

### 1. 不是“XXL-Job分片广播到各实例”

当前V2处理器虽然读取了XXL-Job的分片参数，但没有使用分片编号过滤任务，而是由各实例通过数据库竞争领取。

推荐表述：

> XXL-Job周期性触发Runtime服务的超时交卷处理器，多实例通过数据库批量领取和任务租约并发处理到期任务。

不要主动说“分片广播”，否则面试官查看代码后会发现V2的 `processDue()` 没有使用 `shardIndex`。

### 2. V2任务主要是预创建后激活

强化流程在考试发布并预置考生数据时就创建任务，初始状态为`WAITING`；学生正式进入答题后，再写入实际截止时间并转成`PENDING`。

推荐表述：

> 考试发布时为考生预创建等待状态的超时任务，学生正式进入答题后，根据个人开始时间和考试结束时间计算服务端截止时间，并激活任务。

### 3. 最终答案不直接写普通答案表

当前V2会把选定的答案编码为不可变最终载荷，写入`submission_final_payload`：

- 主键是`submission_id`，避免同一提交重复固化；
- 保存采用的快照版本；
- 使用`GZIP_JSON_V1`压缩；
- 保存SHA-256，用于评分读取时校验数据完整性。

## 建议口述定稿

> 超时交卷的目标，是在考生的服务端答题会话到达截止时间后，由服务端可靠接管交卷，保证不重复、不遗漏，并使用最新有效答案。不能依赖前端自动交卷，因为浏览器可能断网、关闭，而且客户端时间不可信。
>
> 在V2流程中，考试发布并预置考生数据时，会为考生预创建一条`WAITING`状态的超时任务。学生正式进入答题后，服务端根据个人开始时间、考试时长和整场考试结束时间计算实际截止时间，再把任务更新为`PENDING`。
>
> XXL-Job周期性触发Runtime的超时交卷处理器。多个实例在短事务中使用`FOR UPDATE SKIP LOCKED`批量领取到期任务，这样一个实例会跳过其他实例已经锁定的记录，减少锁等待。领取后写入`claimToken`和`leaseUntil`，并把会话从`ANSWERING`条件更新为`AUTO_SUBMITTING`。
>
> 行锁在领取事务提交后就释放，因此后续耗时处理不能继续依赖行锁，而是通过任务租约证明处理权。处理期间还会定期续租；最终提交前再次检查令牌和租约，防止租约失效的旧实例继续提交。
>
> 处理器先读取MySQL中的持久化草稿，再读取Redis快照并比较版本，选择更新的一份；Redis不可用或数据校验失败时使用MySQL兜底。选定答案后，将其编码为带快照版本和SHA-256校验值的不可变最终载荷。
>
> 最后在一个本地事务中锁定任务和会话，写入最终答案，将提交记录从`IN_PROGRESS`更新为`PROCESSING`，将会话更新为`SUBMITTED`，把任务标记为`DONE`，同时写入`SubmissionAccepted` Outbox事件。任何一步失败都会整体回滚。
>
> 事务提交后，Outbox发布器把事件发送到RabbitMQ，收到Publisher Confirm后才标记发布成功。Grading服务消费消息，通过Inbox事件ID去重，然后执行自动判分和主观题任务创建。整个方案提供的是至少一次投递、幂等消费和最终一致性。

## 面试官继续追问

现在进入最容易被问到的并发场景：

> 假设考试在10:00:00截止，学生也恰好在10:00:00点击手工交卷。此时一个Runtime实例正在处理手工交卷，另一个实例已经领取了超时任务。两个流程都可能拿到答案，你如何保证最终只产生一次有效提交？失败的一方应该向前端返回什么状态？

请从以下四个角度回答：

1. 两条链路的加锁顺序；
2. 会话和提交记录的条件状态更新；
3. 任务租约与唯一约束分别起什么作用；
4. 为什么不能仅靠一个Redis分布式锁解决。

> 使用抢占式更新，更新考试会话状态，更新成功的实例可以进入后续的交卷流程，失败的一方向前端返回正在交卷

<details><summary>2 previous messages</summary>

> 继续用 `interview-master` 做压力追问。你的方向是对的，但这版只能算“说出了第一道防线”：条件更新决定谁获得提交权；还没有解释任务锁顺序、提交记录竞争、唯一约束和为什么Redis锁不足。我会按当前手工交卷实现把这四层补齐。
>
> <details><summary>Explored 6 files, 2 searches</summary>
>
> - Searched for `手工交卷|manual submission|lock order|AUTO_SUBMITTING|claimManual|正在交卷` in `MEMORY.md`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/ManualSubmissionService.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/repository/TimeoutTaskRepository.java`
> - Read `./services/exam-runtime-service/src/main/resources/db/migration/V1__runtime_schema.sql`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/messaging/RuntimeOutboxService.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionCoordinator.java`
> - Searched for `create table.*submission_final_payload|submission_id BIGINT PRIMARY KEY|uk_exam_student_submission` in `migration`
> - Read `~/.codex/memories/MEMORY.md`
>
> </details>
</details>

## 本题评价：4/10

你的核心判断正确：通过条件更新，让一个流程获得提交权，另一个流程返回“正在交卷”。

但上一题要求的四个角度里，你只回答了“状态条件更新”，还缺少锁顺序、提交记录约束和Redis锁的不足。更重要的是，当前实现并不只是两个实例同时更新会话来竞争。

## 正确的并发控制过程

手工交卷和超时交卷遵循相同的加锁顺序：

```text
超时任务 → 考试会话 → 提交记录/最终答案 → Outbox
```

两条链路首先都会锁定同一条`submission_timeout_task`，因此它们会被数据库串行化，而不是同时修改后续数据。

### 手工交卷先获得任务锁

手工交卷会：

1. 锁定超时任务；
2. 锁定考试会话；
3. 校验服务端时间尚未到期、会话为`ANSWERING`，且客户端租约有效；
4. 将会话条件更新为`AUTO_SUBMITTING`；
5. 写入最终答案；
6. 将提交记录从`IN_PROGRESS`更新为`PROCESSING`；
7. 写入Outbox事件；
8. 将会话和任务更新为`SUBMITTED`、`DONE`。

随后超时任务再执行时，会看到任务已经`DONE`或会话已经`SUBMITTED`，不会再次提交。

### 超时交卷先获得任务锁

超时流程会把任务更新为`PROCESSING`，把会话推进到`AUTO_SUBMITTING`。

手工交卷等待任务锁释放后，会发现：

- 任务是`PROCESSING`，或者
- 会话是`AUTO_SUBMITTING`

于是返回`SUBMITTING`，表示服务端正在接管交卷。

如果超时交卷已经完成，会话已经是`SUBMITTED`，手工接口返回`PROCESSING`，表示Runtime已经完成提交，后续评分仍在异步处理中。

## 10:00:00边界怎么判定

当前手工交卷的条件是：

```sql
deadline_time > current_timestamp(3)
```

因此，如果数据库时间已经准确到达10:00:00，而截止时间也是10:00:00，手工交卷不能再获得提交权，应由超时流程接管。

这里必须强调使用数据库时间，不能由浏览器时间判断谁先谁后。

## 完整参考回答

> 手工交卷和超时交卷同时发生时，主要通过统一加锁顺序和条件状态迁移保证只有一条链路能够完成提交。
>
> 两条链路都按照“超时任务、考试会话、提交记录和最终答案”的顺序处理，并首先锁定同一条超时任务，因此数据库会把两个流程串行化，避免它们以不同顺序加锁产生死锁。
>
> 如果手工交卷先获得任务锁，它还需要校验会话是`ANSWERING`、数据库时间没有超过截止时间，并且客户端租约有效。校验通过后，将会话条件更新为`AUTO_SUBMITTING`，随后在同一个事务中固化最终答案，把提交记录从`IN_PROGRESS`更新为`PROCESSING`，写入Outbox事件，再把会话和任务更新为`SUBMITTED`和`DONE`。超时流程之后看到已完成状态，就不会重复提交。
>
> 如果超时流程先领取任务，它会把任务更新为`PROCESSING`并将会话推进为`AUTO_SUBMITTING`。手工请求获得锁后看到这个状态，就返回`SUBMITTING`。如果Runtime已经完成最终提交，则返回`PROCESSING`，表示后续评分仍在异步执行。
>
> 唯一约束是最后一道防线：`submission`表通过`exam_id + student_id`避免重复提交记录，最终答案表通过`submission_id`主键避免重复固化，`SubmissionAccepted`还根据提交ID生成确定性的事件ID。但唯一约束不能替代前面的并发协调，否则只能依靠异常回滚，无法清晰表达业务状态。
>
> 我不会只使用Redis分布式锁，因为最终答案、提交状态和Outbox都在MySQL中，Redis锁无法与这些数据放进同一个本地事务；Redis异常或锁TTL提前到期时，也可能出现旧实例继续执行的问题。因此MySQL状态和事务是正确性边界，Redis锁最多只能作为减少竞争的优化，不能作为唯一保证。

## 你这句话需要精确化

原回答：

> 更新失败的一方向前端返回正在交卷。

更准确的说法是：

- 对方正在接管但尚未完成：返回`SUBMITTING`；
- Runtime已经完成，正在等待异步评分：返回`PROCESSING`；
- 截止时间前客户端租约不匹配：返回租约冲突；
- 数据异常或非法状态：回滚并返回业务错误。

## 再回答一次

请控制在60～90秒，必须包含：

1. 两条链路都先锁超时任务，再锁会话；
2. 手工交卷只允许在数据库时间严格早于截止时间时成功；
3. `SUBMITTING`和`PROCESSING`的区别；
4. 唯一约束为什么只是兜底；
5. 为什么Redis锁不能作为正确性边界。

> 同时发生时，首先，两条链路争夺任务锁，如果是手工交卷先获得任务锁，还需要校验会话状态和数据库时间是否小于截止时间，将会话状态更新为自动提交中，然后在同一个事务中提交答案，把提交状态更新为处理中，接着写入outbox事件，最后把会话和任务状态更新为已提交和完成，超时流程之后看到已完成状态，就不会重复提交。如果是超时自动交卷先获得任务锁，他会把任务状态更新为自动交卷中，手工请求即使获取到任务锁，看到这个状态就会返回正在提交中，如果超时交卷已完成，任务状态变成了DONE，手动交卷就会返回处理中，表示后续评分操作在异步执行。`submission`表通过`exam_id + student_id`的唯一约束最为最终兜底，避免重复提交记录，答案表通过`submission_id`主键避免重复提交；我不会只使用Redis分布式锁，因为最终答案、提交状态和Outbox事件都在发生MySQL中，Redis锁无法与这些数据放进同一个本地事务，并且Redis可能发生异常，所以Redis锁最多只能作为减少竞争的优化，不能作为唯一保证

<details><summary>1 previous message</summary>

> 继续使用 `interview-master` 评审这次复答。你的并发主线已经过关；我会把剩余的术语误差压掉，然后进入下一道“租约失效、旧实例恢复执行”的故障题。
</details>

## 本题评价：8/10，校招面试可以过关

你已经完整回答了四层保证：

1. 任务行锁串行化两条链路；
2. 会话状态条件更新决定提交权；
3. 数据库唯一约束兜底；
4. MySQL事务是正确性边界，Redis锁不能替代。

这已经不是背技术名词，而是在解释并发控制方案。

### 还需修正三个术语

- `AUTO_SUBMITTING`虽然字面是“自动提交中”，但手工交卷也会进入这个状态。面试时称为“服务端提交接管态”更合理。
- 超时任务状态是`PROCESSING`，不要说“任务状态变为自动交卷中”。
- 通过`submission_id`主键防止重复固化的是`submission_final_payload`最终载荷表，不是普通答案明细表。

## 口述定稿

> 手工交卷和超时交卷并发发生时，两条链路都按照“超时任务、考试会话、提交记录和最终答案”的顺序操作，并首先争夺同一条超时任务的行锁，因此数据库会把两个流程串行化。
>
> 如果手工交卷先获得任务锁，它还需要校验会话处于`ANSWERING`、数据库当前时间严格早于截止时间，并验证客户端租约。校验成功后，将会话更新为服务端提交接管态`AUTO_SUBMITTING`，然后在同一个事务中固化最终答案，把提交记录从`IN_PROGRESS`更新为`PROCESSING`，写入Outbox事件，最后将会话和任务更新为`SUBMITTED`和`DONE`。超时流程之后看到已完成状态，就不会再次提交。
>
> 如果超时流程先领取任务，它会将任务更新为`PROCESSING`，并把会话推进到`AUTO_SUBMITTING`。手工请求获得锁后看到正在接管，就返回`SUBMITTING`；如果Runtime已经完成最终提交，则返回`PROCESSING`，表示交卷已经受理，评分仍在异步执行。
>
> 数据库约束作为最后一道防线：`submission`表通过`exam_id + student_id`唯一约束避免重复提交记录，`submission_final_payload`通过`submission_id`主键避免重复固化最终答案。同时，提交记录只有处于`IN_PROGRESS`时才能更新为`PROCESSING`。
>
> 不能只使用Redis分布式锁，因为最终答案、提交状态和Outbox事件都在MySQL中，Redis锁无法与这些数据组成同一个本地事务；Redis故障、锁提前过期或者旧实例暂停后恢复，都可能破坏正确性。因此MySQL事务和状态条件是正确性边界，Redis锁最多作为降低竞争的优化。

## 面试官可能继续挑刺

- 为什么必须先锁任务、再锁会话？不同顺序会不会死锁？
- 为什么手工交卷也使用`AUTO_SUBMITTING`，状态命名是否合理？
- 最终答案插入成功，但Outbox写入失败会怎样？
- 唯一约束冲突后是直接报错，还是应该读取现有状态返回幂等结果？
- 为什么返回`PROCESSING`不代表评分已经完成？

## 下一道故障题

> Runtime实例A领取了超时任务，获得了60秒租约，也已经读取并编码了最终答案。随后A发生长时间GC停顿，租约续租失败。租约过期后，实例B重新领取任务并成功完成交卷。此时A恢复运行并继续进入最终提交事务，会发生什么？系统如何阻止A覆盖B的结果？

回答时重点讲：

1. `claimToken`和`leaseUntil`如何校验；
2. 为什么最终提交前还要重新锁任务；
3. A已经在内存中生成答案为什么仍不能提交；
4. 如果A在事务中写了一部分数据后失败，如何恢复。

> 在A恢复运行并进入最终提交事务的时候，会校验A的租约是否有效。因为A的租约已经过期了，所以A是无法提交事务的。

<details><summary>1 previous message</summary>

> 继续使用 `interview-master`。你的结论是对的，但只回答了“租约失效”这一层；面试官还会追问令牌是否已变化、为什么内存答案不算数，以及事务中途失败是否会留下半成品。
</details>

## 本题评价：5/10

核心结论正确，但“租约过期，所以A无法提交事务”不够精确。

A的数据库事务不一定提交失败；更准确地说，**A无法获得最终提交资格，事务可能以无业务写入的方式结束**。

## 完整过程

A恢复后，系统有两道检查：

1. 在进入最终事务前，如果续租线程已经标记`leaseLost`，A会直接终止本次处理。
2. 即使这里发生并发时间差，最终事务仍会重新锁定任务记录，并校验：

    - 任务是否仍为`PROCESSING`；
    - 数据库中的`claimToken`是否仍属于A；
    - `leaseUntil`是否晚于数据库当前时间。

B重新领取任务后，会生成新的`claimToken`；B完成后，任务则变成`DONE`并清空令牌。因此A重新读取任务时，至少会出现令牌不匹配、租约过期或状态不匹配，随后返回“过期任务”，不会继续操作会话和提交记录。

A内存里已经编码好的答案不具有权威性。处理权必须由数据库中的任务状态、令牌和租约共同证明，不能因为A曾经领取过任务就允许提交。

## 参考回答

> A恢复后不会直接提交内存中已经生成的答案。首先，续租线程如果已经发现租约丢失，会设置`leaseLost`，A在进入最终事务前就会终止处理。
>
> 即使这里存在时间差，最终事务仍会重新通过`FOR UPDATE`锁定任务记录，并使用数据库当前时间校验任务状态、`claimToken`和`leaseUntil`。B重新领取任务后已经换成新的令牌；如果B已经完成，任务会变成`DONE`并清空令牌。因此A的旧令牌不再有效，最终提交方法会返回失败，不再写入最终答案、提交状态和Outbox。
>
> A在内存中生成的答案只是计算结果，不代表它仍然拥有提交权。真正的处理权必须以数据库中的租约记录为准，否则暂停后恢复的旧实例可能覆盖新实例的结果。
>
> 如果A已经进入最终事务并写入了一部分数据，后续操作失败时，最终答案、提交状态、会话状态、任务状态和Outbox会随本地事务一起回滚，不会留下半完成状态。Redis快照也只会在事务成功提交后清理。
>
> 还有一种边界情况：如果A在租约有效时已经锁住任务并进入最终事务，B就无法同时重新领取这条任务。即使租约随后在事务执行期间到期，任务行锁仍会阻止B并发修改，直到A提交或回滚。

## 面试官挑刺点

不要只说：

> A的租约过期，所以事务提交失败。

建议改成：

> A会在最终事务中重新锁定并读取任务，以数据库状态校验令牌和租约；校验失败后不执行任何业务写入。若已经发生局部写入，MySQL本地事务会整体回滚。

现在请用45～60秒重新回答一次，必须包含：

- B重新领取后`claimToken`发生变化；
- A最终提交前重新锁定任务；
- 内存答案不等于提交权；
- 局部写入失败后由事务整体回滚。

> 如果实例A是在进入最终事务前停顿的话，由于实例B已经完成了这个事务并且提交了，那么任务状态会变成 Done 已完成，并且会清空令牌。因此如果实例A此时恢复了，它在进入最终事务前会校验任务状态是否为 Processing，并且校验令牌是否还属于它。所以此时实例A是无法进入最终事务的。但是如果实例A是在进入最终事务之后再停顿的，由于在进入最终事务的时候会获取任务记录的行锁，所以即使实例A因此停顿了，实例B也无法领取到这条任务，因为任务行锁已经被实例A锁定了。

<details><summary>1 previous message</summary>

> 继续用 `interview-master`。这次你已经抓住了最关键的“租约负责跨事务所有权、行锁负责事务内排他”边界；我会修正两个非常细的数据库术语，然后把你推进到Outbox最常见的故障追问。
</details>

## 本题评价：8.5/10，已经过关

你的核心判断正确，而且已经讲出了一个很加分的边界：

- A在最终事务外停顿：靠租约和`claimToken`阻止旧实例；
- A已经锁定任务行后停顿：靠MySQL行锁阻止B并发处理。

### 两处术语需要修正

第一，不应说“A无法进入最终事务”。

A可能已经调用了事务方法，并在事务中锁定、读取任务；只是校验发现任务已经`DONE`、令牌不匹配或租约失效，因此**不能继续执行最终提交**。这个事务可以正常结束，只是没有业务写入。

第二，“开启事务”不等于“立即获得行锁”。

只有执行`SELECT ... FOR UPDATE`锁定任务记录后，才真正持有行锁。如果A刚开启事务、但还没执行加锁SQL，B仍可能先拿到锁。

## 精炼口述版

> 如果A在最终事务前停顿，租约到期后B可以重新领取任务，并生成新的`claimToken`。B完成交卷后，任务会变成`DONE`并清空令牌。A恢复后即使进入最终事务，也必须重新通过`FOR UPDATE`读取任务，校验状态、令牌和租约。此时校验不会通过，所以A不能继续写最终答案和Outbox。
>
> 如果A已经在最终事务中通过`FOR UPDATE`锁定了任务记录，那么只要数据库事务和连接仍然有效，B扫描到这条记录时会因为`SKIP LOCKED`而跳过，不会和A并发提交。A后续成功就整体提交，发生异常则整个事务回滚并释放行锁，之后B才能重新领取。
>
> 所以任务租约解决的是事务之外实例宕机或停顿后的接管问题，数据库行锁解决的是最终事务内部的并发互斥问题，两者作用范围不同。

还有一个高级细节：如果A已经校验租约并持有任务行锁，之后租约在事务执行期间到期，B仍无法处理这条任务，因为行锁还在。此时不存在A、B同时提交的问题。

## 下一道高频题：Outbox故障窗口

> Runtime已经成功提交超时交卷事务，`SubmissionAccepted`记录也已经写入Outbox，但此时RabbitMQ不可用，会发生什么？
>
> 如果Outbox发布器已经把消息成功发送到RabbitMQ，却在将Outbox记录更新为`PUBLISHED`之前宕机，又会发生什么？
>
> Grading服务先写入Inbox记录，随后评分过程中发生异常，这条消息下次重试时会不会被Inbox错误地当成已消费？

请从以下四点回答：

1. 为什么业务事务不直接依赖RabbitMQ；
2. Outbox如何重试；
3. 为什么消息可能重复；
4. Inbox写入和评分为什么必须在同一个事务中。

> 如果提交事件已经写入了 Outbox，但是如果此时 RabbitMQ 不可用的话，在这种情况下，由于消息的发送是由 Outbox 发布器扫描 Outbox 表中的记录发送到 RabbitMQ。如果此时 RabbitMQ 不可用，那么发布器就会捕获异常并计算下一次的重试时间，并把这条记录放回待发送队列。不把这个发送消息的过程与之前交卷的流程写入一个事务，是因为要彻底避免分布式双写不一致以及保证高可用。因为如果先写 DB 后发 MQ，如果 DB 提交成功，但在发 MQ 时遇到网络闪烁，Rabbit 应用宕机，就会导致数据库已入库但事件彻底失效。考生的试卷将永远停留在 Processing 状态。如果先发 MQ 后写 DB 的话，当 MQ 发送成功后，如果 DB 事务在提交时发生了异常回滚，MQ 发出了消息就无法撤回，阅卷服务就会收到一份根本没有交卷成功的数据判分。所以交卷业务一定是要与发送消息的业务是要隔离的。如果消息已经成功发送到 RabbitMQ，但更新记录时宕机了，此时的话 Outbox 的发布器会再发送一条记录到 RabbitMQ 中，并把这条记录把 Outbox 中的记录更新为已发送。由于下游阅卷服务接收到消息时会先写入 inbox 表去重，所以消息重复的话是没有关系的，因为消费是只会消费一次的。在阅卷服务的评分过程中发生异常的时候，会回滚之前的 inbox 记录，inbox 表的记录不会被 inbox 错误地当成已消费。Inbox 写入和评分放在同一个事务，就是防止评分失败，保证阅卷可用。

<details><summary>3 previous messages</summary>

> 继续使用 `interview-master`。这次回答已经达到校招中的高分水平，特别是你把“先DB/先MQ”两种错误顺序都讲清楚了。我会收紧几处容易被挑刺的绝对化表述，再进入集中到期时的容量追问。
>
> <details><summary>Explored 3 files, a search, ran a command</summary>
>
> <details><summary>Explored 3 files, a search</summary>
>
> - Searched for `Outbox|RabbitMQ recovery|500/500|10,000|capacity|容量|Inbox|exactly` in `MEMORY.md`
> - Read `./platform/exam-outbox-support/src/main/java/com/ekusys/exam/common/outbox/OutboxRepository.java`
> - Read `./platform/exam-outbox-support/src/main/java/com/ekusys/exam/common/outbox/OutboxPublisher.java`
> - Read `./services/exam-grading-service/src/main/java/com/ekusys/exam/grading/messaging/SubmissionAcceptedConsumer.java`
>
> </details>
>
> - Ran `rg -n "63\.4|63\.6|63\.779|10000|P99|zero omissions|500/500" deploy/load-test documents 2>/dev/null | head -160`
>
> </details>
>
> Context compaction
>
> Source: automatic
> Status: completed
</details>

这轮可以给 **8.5/10**，已经达到校招项目深挖中的高分水平。你准确讲清了双写不一致、发布失败重试、发布状态更新失败导致重复投递，以及 Inbox 与评分事务的一致性。

需要收紧 4 个表述：

- 不是“放回待发送队列”，而是把 Outbox 数据库记录由 `SENDING` 恢复为 `PENDING`，设置 `next_retry_time`；超过最大次数会进入 `FAILED`，需要告警或人工重放。
- 不是“彻底避免所有不一致”，而是通过“业务数据与 Outbox 同一本地事务”规避 DB 与 MQ 的分布式双写问题。
- 不是“消息只消费一次”，而是“消息可能重复投递，但通过 Inbox 保证业务有效处理一次”。
- Inbox 与评分同事务保证的是“失败后不会留下错误的去重标记”，不能笼统说成保证整个阅卷系统高可用；重试耗尽后仍可能进入死信队列。

## 推荐口述版

> 我们采用 Transactional Outbox，核心不是把交卷和事件完全分开，而是把提交记录、最终答案、会话状态和 Outbox 事件放在同一个 MySQL 本地事务中提交；只有真正向 RabbitMQ 发送消息的网络操作，才放到事务提交后异步执行。
>
> 这样可以规避直接双写的问题。如果先提交数据库再发送 MQ，数据库成功而 MQ 发送失败，就可能出现试卷已经提交但没有进入评分流程；如果先发送 MQ 再提交数据库，MQ 成功而数据库回滚，阅卷服务又可能处理一份实际没有提交成功的数据。
>
> Outbox 发布器会领取待发布记录并生成租约。如果 RabbitMQ 不可用，发布失败后会记录失败次数，将事件恢复为 `PENDING`，并根据退避策略设置下一次重试时间；超过最大重试次数后进入 `FAILED`，由监控告警或人工重放处理。
>
> 如果 RabbitMQ 已经确认收到消息，但发布器在把 Outbox 更新为 `PUBLISHED` 前宕机，租约过期后其他实例会重新发送，所以系统允许重复投递，提供的是至少一次投递语义。
>
> 阅卷服务收到消息后，会在同一个数据库事务中写入 Inbox 去重记录并执行评分。重复消息插入 Inbox 失败后直接跳过，因此消息可以重复到达，但业务只产生一次有效处理。如果评分过程中抛出异常，Inbox 和评分结果会一起回滚，后续消息重试不会被错误地判定为已经消费。
>
> 因此这套方案的准确语义是：本地事务保证业务数据与事件同时产生，Outbox 保证故障恢复后的至少一次投递，Inbox 保证幂等消费，最终实现交卷与评分之间的最终一致性。

如果需要补充结果，可以说：

> 在当前隔离故障测试中，我们对 500 次提交中断 RabbitMQ，期间产生了 110 次发布失败；RabbitMQ 恢复后，500 条 Outbox 最终全部进入 `PUBLISHED`。不过这是测试环境的故障注入结果，不能直接等同于生产高可用结论。

该数据可在[故障注入报告](./documents/超时交卷V2隔离压测与故障注入报告.md:75)中核对。

## 面试官可能继续追问

1. Publisher Confirm 成功，是否代表阅卷服务已经处理完成？
2. Outbox 一直失败并超过最大重试次数怎么办？
3. Inbox 表持续增长，如何归档或清理？
4. 如果两个阅卷实例同时收到同一个事件，如何保证只有一个实例评分？
5. 为什么不用 RabbitMQ 事务消息或分布式事务？

## 下一道高概率追问

当前隔离压测中，4 个 Runtime 实例能够完成 10,000/10,000 条超时交卷，但数据库完成时间的 P99 为 47.021 秒，没有达到 P99 小于30秒的目标。

请你以候选人的身份回答：

> 为什么增加 Runtime 实例和工作线程不一定能继续提高吞吐量？你会怎样定位这47秒主要消耗在哪里，又会按照什么顺序优化？

> 增加 runtime 实例和工作线程不一定能提高吞吐量，是因为虽然我们使用了 for update skip locked 避免了领取任务时的相互锁阻，但是在执行交卷状态事务的时候，每个线程需要向同一张 MySQL 写入更新和插入，线程数越多，MySQL InnoDB 锁、表意向锁、redo log 争用越剧烈，大量的事务在数据库内核排队，第二点，因为数据库连接池是有限的，增加 runtime 工作线程只会导致大量线程在阻塞上增加这个阻塞时间，而且过多的工作线程会引发 CPU 频繁地切换上下文，导致时间浪费在 CPU 的线程调度上。
> 对于这47秒的消耗，首先将这个过程，把这个业务流程的时间消耗分为排队与等待耗时，答案的装配与序列化耗时，还有最后最终的本地事务耗时，将业务流程的时间分为这三个阶段。然后分别查看这三个阶段的消耗时间。优化的话，首先是优化数据库连接池、线程池以及调度参数的一个配比。 其次的话是消除失误之外的网络与计算瓶颈。这块需要优化 Redis 草稿的读取，要保证 Redis 连接池充足，降低从 Redis 中拉取草稿的时间，还要优化这个答案的压缩算法，让这个答案压缩得更快。第三阶段的话主要是压缩 MySQL 本地失误的一个持有时间。最后的话就是只能是优化架构了。

<details><summary>2 previous messages</summary>

> 我继续按 `interview-master` 的方式点评。这一轮方向正确，但“47 秒怎么定位”还需要从概念性分段升级到可执行的指标、实验和瓶颈证据；我也会核对当前压测口径，避免把推测说成结论。
>
> <details><summary>Explored 5 files, 2 searches</summary>
>
> - Read `./.agents/skills/interview-master-skill-main/SKILL.md`
> - Searched for `47\.021|P99|10,000|10000|连接池|线程池` in `documents`
> - Searched for `EkuExam Cloud resume|timeout auto|47\.021|Outbox|interview` in `MEMORY.md`
> - Read `./documents/超时交卷V2隔离压测与故障注入报告.md`
> - Read `./documents/超时交卷V2隔离压测与故障注入报告.md`
> - Read `~/.codex/memories/MEMORY.md`
> - Read `~/.codex/memories/MEMORY.md`
>
> </details>
</details>

这轮我给 **7.5/10**。你已经具备正确的容量分析思路，但有两处会被资深面试官追着打：把“写同一张表”直接等同于锁竞争，以及在没有指标前就决定优化 Redis 和压缩算法。

## 回答得好的地方

- 认识到扩容存在瓶颈拐点，不是线程越多吞吐越高。
- 提到了连接池排队、数据库资源竞争、线程上下文切换。
- 开始按阶段拆解耗时，而不是笼统归因于“数据库慢”。
- 优化顺序里考虑了线程池、连接池和事务持有时间。

## 需要修正的关键点

### 1. “写同一张表”不等于“发生严重锁冲突”

一万名学生通常更新不同的任务、会话和提交记录。表意向锁中的 `IX` 与 `IX` 可以兼容，因此“表意向锁竞争严重”这个说法不准确。

更严谨的说法是：

> 即使不同事务操作不同的业务行，不一定直接发生行锁互斥，但它们仍会竞争数据库连接、CPU、Buffer Pool、磁盘 I/O、Redo Log 刷盘和事务提交能力；如果存在索引范围扫描、共享热点记录或不一致的加锁顺序，才会进一步产生行锁等待或死锁。

除非已经从 `performance_schema`、InnoDB 锁等待或死锁日志中拿到证据，否则不能直接断言主要瓶颈就是行锁。

### 2. 47秒不是单笔事务执行了47秒

这里统计的是从任务到期到完成的累计时间，主要可以拆成：

1. 到期后等待任务被扫描和领取；
2. 任务领取后在线程池中的排队时间；
3. 读取草稿、校验、组装、序列化和压缩；
4. 最终事务获取连接、加锁、执行 SQL 和提交；
5. 如果看客户端指标，还要加上 Gateway、状态轮询和退避等待。

因此要同时区分：

- **排队时间**：任务到期到开始处理；
- **服务时间**：真正执行业务逻辑的时间；
- **客户端观察时间**：用户多久看到提交完成。

### 3. Redis和压缩算法属于待验证假设

当前实现以数据库主草稿作为可靠性边界，Redis更多是兼容快速路径。不能在没有分段指标前就说“先优化 Redis 读取和压缩算法”。

更好的表述是：

> 如果埋点显示草稿读取或 GZIP、SHA-256 占比明显，再针对这部分优化；如果主要时间消耗在 Hikari 获取连接和 MySQL 提交，则优化 Redis 几乎不会改善总体P99。

### 4. 应该先测量，再调参

当前实验已经提供了很好的面试证据：

- 4实例、8工作线程下，将 MySQL连接上限从151提高到300，数据库P99一度从 `41.041s` 降至 `33.583s`，说明连接排队是瓶颈之一。
- 继续把每实例工作线程从8提高到10后，P99反而上升至 `42.880s`，说明系统进入资源饱和区。
- 去掉客户端状态轮询后，后台P99仍为 `31.102s`，说明轮询放大了延迟，但最终化热路径本身也需要优化。
- 标准基线复测的P99又达到 `53.548s`，说明测试结果存在波动，需要隔离负载机、Runtime和MySQL，并进行多轮复测后才能得出容量结论。

相关证据见[超时交卷V2隔离压测与故障注入报告](./documents/超时交卷V2隔离压测与故障注入报告.md:42)。

## 推荐口述版

> 增加 Runtime 实例和工作线程只能提高应用层并行度，不能线性提高整个系统的吞吐量，因为最终所有请求仍然会汇聚到 MySQL、Redis等共享资源。
>
> `FOR UPDATE SKIP LOCKED` 主要解决任务领取时的相互阻塞，但后续每个任务还需要获取数据库连接，读取草稿，并在最终事务中更新任务、会话、提交记录、最终答案和 Outbox。即使不同学生操作不同的行，不发生直接的行锁冲突，也仍然会竞争数据库连接、CPU、Buffer Pool、Redo Log和磁盘刷盘能力。当并发超过数据库处理能力后，新增线程只会增加连接等待、事务排队和上下文切换。
>
> 定位47秒时，我不会先猜具体组件，而是把到期到完成的时间拆成四段：任务等待领取时间、执行器排队时间、草稿读取和答案组装时间、最终事务时间。同时采集执行器队列长度、Hikari连接获取等待、MySQL慢查询、锁等待、CPU、磁盘I/O、Redo刷盘以及Redis延迟。
>
> 我还会通过单变量实验定位瓶颈。例如保持4个Runtime和8个工作线程不变，只调整数据库连接预算；再保持连接数不变，单独调整工作线程；然后分别比较有无状态轮询的结果。
>
> 当前实验中，连接上限增加后P99一度从41.041秒下降到33.583秒，说明连接排队确实存在；但把每实例工作线程从8增加到10后，P99又上升到42.880秒，说明盲目增加并发会反向压垮数据库。
>
> 优化时，我会先控制状态轮询造成的读流量并建立全局连接预算，然后根据埋点缩短最终事务，例如把压缩、哈希和答案组装放在事务外，减少重复查询，确认索引命中，并缩短持锁时间。如果单机MySQL已经成为确定瓶颈，再考虑读写资源隔离、任务削峰或数据库层面的容量扩展，而不是直接继续增加Runtime线程。

## 下一道压力追问

> 你刚才提到了 InnoDB 锁竞争，但一万名学生更新的是不同任务行和会话行，`IX` 意向锁之间也是兼容的。既然不存在明显的同一行热点，为什么工作线程从8增加到10后性能仍然下降？你如何证明究竟是行锁、连接池、CPU、磁盘I/O还是Redo刷盘导致的？

这一题不要泛泛回答“看监控”，要说出具体指标、工具和对照实验。

> 在没有行锁冲突的情况下增加线程性能下降的话，一般是由于引发了更加底层的全局共享资源的竞争。首先是 InnoDB 全局的内存锁争用，然后是磁盘 I/O 和 redo log 的一个效率的不匹配。当磁盘 I/O 达到上限时，由于 latch 竞争打乱了阻提交，拉长了排队时间，最后的话就是操作系统线程上下文切换和 CPU 缓存被击穿。当并发线程超过 CPU 承载上限后，操作系统频繁发生非自愿上下文切换，导致 CPU 指令流水线清空，缓存频繁失效，CPU 的算力大量浪费在内核调度的开销上。
> 对于究竟是哪里发生瓶颈的话，可以使用具体的 Linux 内核工具性能视图和定量指标进行一些排查。首先排查是不是连接池的瓶颈的话，使用 micrometer 抓取 HikariCP暴露的指标，证明是否是 CPU 与上下文切换过载，使用 pidstat 指令去检查。

<details><summary>2 previous messages</summary>

> 继续用 `interview-master` 做压力面点评。你的回答已经能主动下沉到数据库、操作系统层，但目前“机制名词”多于“证据链”，而且回答停在 Hikari 和 `pidstat`，还没有完成 MySQL、磁盘与对照实验部分。
>
> <details><summary>Ran a command</summary>
>
> - Ran `sed -n '1,999p' .agents/skills/interview-master-skill-main/SKILL.md >/dev/null && sed -n '60,68p' documents/超时交卷V2隔离压测与故障注入报告.md && sed -n '96,106p' documents/超时交卷V2隔离压测与故障注入报告.md`
>
> </details>
</details>

如果你在这里停下，我会给 **6.5/10**。底层意识不错，但主要问题是：提出了很多可能原因，却还没有形成“现象—指标—结论—实验”的完整证据链。

## 两个容易被追问的表述

第一，“InnoDB全局内存锁”过于模糊。InnoDB内部确实存在 mutex、rw-lock、latch 等同步机制，但不能把它统称为一个全局锁。除非你能说出对应的等待事件和监控证据，否则建议改成：

> InnoDB内部同步资源、Redo写入、Buffer Pool和存储I/O都可能成为共享瓶颈，需要通过等待事件确认。

第二，“CPU缓存被击穿”不准确。“缓存击穿”通常用于缓存系统。这里应该说：

> 线程数量超过有效并行度后，上下文切换、CPU迁移和Cache Miss增加，降低单位CPU时间内完成的有效指令数。

## 完整排查证据链

| 怀疑点 | 关键指标 | 工具与判断 |
|---|---|---|
| Hikari连接排队 | Active、Pending、Acquire P99、Timeout | Micrometer；如果Active长期到上限、Pending持续大于0、获取连接耗时升高，说明连接获取是直接等待点 |
| 行锁竞争 | Lock Wait、事务锁等待时间、死锁 | `performance_schema.data_lock_waits`、InnoDB Status；如果基本为0，就排除行锁是主要原因 |
| SQL效率 | SQL调用次数、平均/P95耗时、扫描行数 | `events_statements_summary_by_digest`、慢查询日志、`EXPLAIN ANALYZE` |
| Redo瓶颈 | `Innodb_log_waits`、Redo写入及刷盘延迟 | 如果工作线程增加后Log Wait同步增长，说明提交吞吐受Redo限制 |
| 磁盘I/O | `await`、队列深度、吞吐量、I/O Wait | `iostat -xz 1`、`pidstat -d` |
| CPU与调度 | CPU利用率、运行队列、非自愿切换 | `pidstat -u -w -p PID 1`、`vmstat 1`、`mpstat -P ALL 1` |
| JVM自身 | GC暂停、分配速率、线程数 | Micrometer/JFR；避免把GC或容器CPU限额误判成MySQL问题 |
| 应用排队 | 线程池Active、Queue Size、Rejected | 判断任务是在执行器等待，还是进入数据库后才变慢 |

特别注意：看到Hikari的 `Active=max`、`Pending>0`，只能证明线程在等待连接，不能直接证明“连接池太小”。也可能是数据库变慢，导致连接长期无法归还。

## 推荐口述版

> 在没有明显行锁冲突的情况下，增加线程后性能下降，说明瓶颈可能已经从应用并行度转移到共享资源，例如数据库连接、CPU、Redo刷盘、Buffer Pool或者磁盘I/O。线程数超过系统有效并行度后，还会增加上下文切换和CPU Cache Miss。
>
> 但这些都只是候选假设，我会建立指标证据链，而不是直接断言是某个InnoDB锁。
>
> 首先检查Hikari的Active、Pending和连接获取P99。如果Active长期达到上限、Pending持续增长，说明连接获取是直接等待点。但这不代表应该立即扩容连接池，因为也可能是MySQL处理变慢，导致连接归还不及时。
>
> 接下来检查MySQL的SQL Digest、慢查询、`data_lock_waits`、`Innodb_log_waits`和事务提交延迟。如果锁等待接近零，但Redo等待和磁盘延迟随着并发升高，说明瓶颈更可能在事务提交和存储层。
>
> 操作系统层面，我会通过`pidstat`观察进程CPU和非自愿上下文切换，通过`vmstat`观察运行队列和I/O Wait，通过`iostat`观察磁盘请求延迟和队列深度，同时排除JVM GC和容器CPU限额。
>
> 最后进行单变量实验。当前测试中，保持4个Runtime、每实例8个工作线程不变，提高MySQL连接上限后，数据库P99一度从41.041秒降到33.583秒；但继续把工作线程从8提高到10，P99又上升到42.880秒。这能够证明系统存在并发拐点，但还不能单独证明是行锁，需要结合上述指标确定究竟是连接、CPU、Redo还是I/O饱和。

## 下一道追问

> 监控显示Hikari连接池的Active长期等于最大值，Pending持续大于0，连接获取P99明显升高，但MySQL CPU只有45%，磁盘利用率也不高。你能否直接得出“连接池太小”的结论并扩大连接池？怎样区分是连接池配置太小，还是SQL和事务持有连接时间过长？

> 换个方向询问，我对这些内容不太熟悉（这属于哪方面的知识，应该怎么学习？）

<details><summary>2 previous messages</summary>

> 我会继续使用 `interview-master`，但把训练难度降回校招合理范围。刚才那组问题属于性能工程深挖，不应成为你现阶段的主线；我先帮你划清“必须掌握”和“加分项”，随后换到 Redis 答题快照方向。
>
> <details><summary>Ran a command</summary>
>
> - Ran `sed -n '1,999p' .agents/skills/interview-master-skill-main/SKILL.md >/dev/null`
>
> </details>
</details>

刚才的问题主要属于以下几个领域的交叉：

- **性能工程**：吞吐量、延迟、P95/P99、容量评估、压测。
- **数据库原理**：连接池、事务、行锁、Redo Log、Buffer Pool、磁盘刷盘。
- **Java并发**：线程池、任务队列、上下文切换、背压。
- **可观测性**：通过指标、日志和链路追踪定位耗时。
- **Linux性能分析**：CPU、内存、磁盘I/O和线程调度。

这对校招生来说属于进阶加分项，不需要现在掌握到 InnoDB Latch、CPU流水线、`perf` 等内核细节。刚才继续往下问，难度已经更接近有经验的后端工程师。

## 你需要掌握到什么程度

针对你的简历，建议分三层。

第一层必须掌握：

- 吞吐量、并发量、响应时间、P99分别是什么。
- 为什么线程越多不一定越快。
- 线程池和数据库连接池分别限制什么。
- 慢SQL、锁等待、连接等待的基本区别。
- 压测为什么要控制变量，为什么不能只测一次。
- “一万条最终完成”不等于“性能指标达标”。

第二层作为加分项：

- InnoDB行锁、Buffer Pool、Redo Log的基本作用。
- HikariCP的Active、Idle、Pending指标。
- JVM的CPU、GC、线程池队列指标。
- `top`、`pidstat`、`iostat`的基本用途。
- 如何拆分排队时间、业务处理时间和事务时间。

第三层暂时可以不学：

- InnoDB内部Latch和Mutex实现。
- CPU流水线、Cache Miss的底层分析。
- `perf`、火焰图和eBPF。
- 操作系统内核调度细节。
- MySQL源码级事务提交过程。

面试中不要主动提第三层名词。说得越底层，面试官越可能要求你解释实现原理。

## 建议学习顺序

1. 先学习性能指标：QPS、吞吐量、平均值、P95/P99、饱和点。
2. 再学习Java线程池和Hikari连接池，理解线程、连接和数据库容量的关系。
3. 学习MySQL事务、索引、行锁、Buffer Pool和Redo Log。
4. 学习Micrometer、Prometheus，以及慢查询日志等基本观测手段。
5. 最后结合项目压测：每次只改变一个变量，记录结果并解释现象。

最有效的练习方式不是背工具命令，而是针对每个问题回答四句话：

> 我观察到了什么现象？
> 我有哪些候选假设？
> 我用什么指标区分这些假设？
> 实验结果支持什么结论？

另外，你的简历写了“高并发作答场景”，因此容量问题仍可能被问到。你不需要掌握数据库内核，但至少要能解释“为什么不能盲目增加线程”。如果不准备这部分，建议把简历中的“高并发”收紧为“集中到期交卷场景”。

## 换一个方向：Redis答题快照

下一道项目题：

> 学生先后保存了第10版和第11版答案，但由于网络延迟，第11版请求先到服务端，第10版后到。你如何防止旧答案覆盖新答案？Redis快照、MySQL草稿和最终交卷之间又如何选择最新可信版本？

请先按你的理解回答，不要求一次答完整。

> 保存答题快照时，redis会校验到达的快照版本号是否大于redis中的最新版本号，不大于则无法覆盖；最终交卷时，从Redis获取最新版本快照，如果版本大于MySQL中的版本，并且答案内容校验完整后就使用Redis的快照，否则使用MySQL的

<details><summary>2 previous messages</summary>

> 继续用 `interview-master`。你的核心思路是对的，但“版本大就可信”和“Redis优先”这两句话还不够严谨； אוה我会结合当前实现校准版本比较、完整性校验和最终兜底边界。 pointers.
>
> <details><summary>Explored 2 files, a search, ran 2 commands</summary>
>
> - Ran `sed -n '1,999p' .agents/skills/interview-master-skill-main/SKILL.md >/dev/null
> rg -n "storedVersion|serverRevision|clientSeq|draftVersion|Redis.*version|submission_draft_payload|GZIP_JSON_V1|final_payload|load.*draft|select.*draft|snapshot" services/exam-runtime-service/src/main/java services/exam-runtime-service/src/main/resources -g '*.java' -g '*.xml' -g '*.sql' -g '*.yml' -g '*.yaml' | head -220`
>
> - Ran `sed -n '1,235p' services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/ExamSnapshotService.java
> sed -n '1,285p' services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/SnapshotDraftPayloadService.java
> sed -n '1,150p' services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionService.java
> sed -n '1,130p' services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionFinalizationService.java 2>/dev/null || true`
>
> <details><summary>Explored 4 files, a search</summary>
>
> - Searched for `loadLatestDraft|loadLatestBySubmissionId|SnapshotDraft|finalPayload|finalize` in `TimeoutSubmissionCoordinator.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionCoordinator.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/SnapshotFlushQueue.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/TimeoutSubmissionCoordinator.java`
> - Read `./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/SnapshotFlushQueue.java`
>
> </details>
>
> </details>
</details>

这轮按“通用设计思路”可以给 **7/10**，但按照当前项目实现只能给 **5.5/10**：你描述的是旧版“Redis主快照、MySQL兜底”模型，而当前实现已经改成了“**MySQL持久草稿为主，Redis作为缓存兼容路径**”。

## 你答对的部分

- 使用单调递增版本防止第10版覆盖第11版。
- 最终交卷不能无条件相信Redis。
- Redis载荷需要经过校验才能使用。
- Redis不可用时应当有MySQL兜底。

但“答案内容校验完整”不够准确。考生可能本来就没有答完所有题，系统不能因为存在空题就拒绝快照。这里应该说“校验载荷完整性”，例如编码格式、身份、反序列化和SHA-256摘要。

## 当前实现的准确机制

现在存在两个版本概念：

- `clientSequence`：客户端请求序号，用来判断请求先后和请求幂等。
- `serverRevision`：服务端权威版本，用来检测并发修改和过期分支。

保存时：

1. 锁定考试会话，校验会话状态、服务端截止时间和客户端租约。
2. 如果请求序号小于已保存序号，拒绝旧快照。
3. 如果序号相同、客户端和内容摘要相同，作为幂等重试返回成功。
4. 如果序号相同但内容不同，返回序列冲突，防止同一个序号对应两份答案。
5. 如果请求携带的`baseServerRevision`与服务端当前版本不一致，也拒绝更新。
6. 接受后先将压缩载荷、SHA-256、客户端序号和服务端版本写入MySQL。
7. MySQL事务成功后，再尽力同步到Redis；Redis失败不会推翻已经持久化的草稿。

对应实现在[SnapshotDraftPayloadService.java](./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/SnapshotDraftPayloadService.java:37)和[ExamSnapshotService.java](./services/exam-runtime-service/src/main/java/com/ekusys/exam/runtime/service/ExamSnapshotService.java:55)。

最终交卷时，当前V2流程会优先按`submissionId`读取MySQL中的持久草稿，解压并验证SHA-256。只有持久草稿不存在时，才进入旧数据兼容路径，比较旧版MySQL草稿和Redis快照。

## 推荐口述版

> 当前实现使用客户端序号和服务端版本共同防止旧快照覆盖新快照。
>
> 客户端每次保存都会递增`clientSequence`，同时携带上次服务端返回的`baseServerRevision`。服务端在事务中锁定会话并读取当前草稿：序号更小的请求直接拒绝；序号相同且内容摘要相同的请求视为幂等重试；序号相同但内容不同则返回冲突；基础服务端版本不一致也拒绝写入。
>
> 快照被接受后，服务端递增`serverRevision`，先将GZIP压缩后的答案、SHA-256摘要和版本信息持久化到MySQL，再尽力写入Redis。因此Redis故障不会导致已经确认保存的答案丢失。
>
> 超时交卷时优先读取MySQL持久草稿并校验编码和SHA-256，然后生成不可变的最终答案。只有兼容旧数据时，才比较Redis与旧版MySQL草稿的版本。因此可靠性边界是MySQL，Redis主要用于缓存和旧路径兼容。

## 下一道追问

> 第11版快照已经成功写入MySQL，但随后写Redis失败；此时接口应该返回成功还是失败？考试到期后自动交卷会不会丢失第11版答案？为什么？
