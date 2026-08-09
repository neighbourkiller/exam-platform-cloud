# Repository Guidelines

## 协作约定

1. 默认使用中文回复。
2. 在 Windows 上调用 PowerShell 时，优先使用 PowerShell 7：`C:\Program Files\PowerShell\7`。
3. 提交信息使用简短、明确的中文祈使句。
4. 修改前先确认目标所属模块，并以当前代码、配置和迁移为依据，不根据旧文档猜测实现。

## 仓库架构与目录路由

EkuExam Cloud 是面向管理员、教师和学生的在线考试平台。当前仓库采用 Maven 多模块微服务架构：网关统一入口，Nacos 负责注册与配置，服务之间通过 OpenFeign 和 RabbitMQ 协作，各业务服务拥有独立 MySQL Schema。

### 技术基线

- 后端：Java、Spring Boot、Spring Cloud、Spring Security、MyBatis-Plus。
- 服务治理：Gateway、Nacos、OpenFeign、XXL-Job。
- 数据与消息：MySQL、Redis、Flyway、RabbitMQ、Outbox/Inbox、MinIO。
- 前端：Vue、Vite、Element Plus、Pinia、Axios、ECharts。
- 测试：JUnit、Mockito、Testcontainers。

具体版本以聚合 `pom.xml`、前端 `package.json` 和 `compose.yaml` 为准。

### 目录路由

- `services/`：各业务微服务，新后端功能的默认落点；进入后继续遵守 [`services/AGENTS.md`](services/AGENTS.md)。
- `apis/`：跨服务 DTO 和 Feign 契约，不放业务实现；契约规则见 [`apis/AGENTS.md`](apis/AGENTS.md)。
- `platform/`：跨服务复用的基础能力，不放服务专属业务。
- `src/main/resources/frontend/`：当前 Vue 前端，不属于旧单体；前端规则见 [`src/main/resources/frontend/AGENTS.md`](src/main/resources/frontend/AGENTS.md)。
- `deploy/`：本地部署、Nacos 发布和运维文档；部署规则见 [`deploy/AGENTS.md`](deploy/AGENTS.md)。
- `src/main/java/`、`src/main/resources/db/`：旧单体遗留，不是新功能和新迁移的默认落点。

### 服务职责

- `exam-gateway`：API 网关、路由、跨域、限流与资源服务器。
- `exam-iam-service`：登录、令牌、用户、角色与认证限流。
- `exam-academic-service`：课程、班级、教师与学生档案。
- `exam-content-service`：题库、试卷、快照与 MinIO 资源。
- `exam-management-service`：考试安排、发布、监考策略与处置。
- `exam-runtime-service`：开考、答题快照、客户端租约、防作弊与交卷；专属规则见 [`services/exam-runtime-service/AGENTS.md`](services/exam-runtime-service/AGENTS.md)。
- `exam-grading-service`：客观题判分、主观题批阅与成绩事件。
- `exam-reporting-service`：成绩投影、统计看板与管理员监控。

## 必须遵守的系统边界

- 新后端功能进入对应的 `services/exam-*-service`，不要默认修改根目录旧单体实现。
- 跨服务调用先在 `apis/` 定义稳定契约，再通过 Feign Client 或事件协作；禁止直接访问其他服务数据库。
- 每个服务只维护自己的 Schema、Flyway 迁移和 Outbox 表。跨服务一致性依赖事件和幂等消费，不依赖分布式事务。
- `platform/` 只承载已明确需要跨服务复用的基础能力，业务专属代码不得为了复用而过早下沉。
- Schema 变更必须在所属服务新增版本化 Flyway 脚本；禁止修改已发布迁移或用手工改库替代迁移。
- 业务数据和 `outbox_event` 应在同一事务写入；消费者使用 `inbox_event` 或等价约束去重。可靠性语义是“至少一次投递 + 幂等消费”，不得声称绝对不重复或绝对不丢失。
- 变更公共事件、共享队列、死信交换机、重试参数或 Outbox 状态机时，必须定位全部生产者、消费者和拓扑声明方，并评估兼容性与发布顺序。
- `compose.yaml` 是当前微服务栈的标准入口；`docker-compose.yml` 是历史文件，不得混用。
- 涉及 Outbox 上线、重放或清理时，先阅读 [`deploy/OUTBOX_OPERATIONS.md`](deploy/OUTBOX_OPERATIONS.md)；涉及快照积压或失败时，先阅读 [`deploy/SNAPSHOT_FLUSH_OPERATIONS.md`](deploy/SNAPSHOT_FLUSH_OPERATIONS.md)。

## 构建与验证

后端优先验证目标模块及其依赖，将示例模块路径替换为实际模块：

```bash
./mvnw -pl services/exam-runtime-service -am test
```

需要全仓验证或构建可部署 JAR 时使用：

```bash
./mvnw test
./mvnw -B clean package -DskipTests
```

前端改动至少执行：

```bash
cd src/main/resources/frontend
npm run build
```

- Runtime 与 Outbox 相关模块包含 Testcontainers 测试；运行前确认 Docker 可用，并区分基础设施不可用与业务回归。
- 以命令退出码和测试汇总判断结果，不把普通 Spring Boot、Mockito 或 Surefire 警告误报为失败。
- 不得声称运行了实际未执行的测试、构建或手工验证。

## 安全与机密

- `.env.microservices`、`deploy/secrets/`、密码、JWT 私钥、服务间密钥和容器环境变量不得提交、打印、复制到文档或出现在截图中。
- 不在源码、测试数据、日志或错误信息中硬编码或泄露机密。
- 修改 Nacos、Compose 或部署配置时遵守 [`deploy/AGENTS.md`](deploy/AGENTS.md)，并区分仓库配置、已发布配置和运行时状态。

## 完成标准

- 检查变更全部位于正确模块，且没有夹带无关重构、机密或构建产物。
- 检查数据库、消息、公共契约、Nacos 配置和安全边界的影响；需要协调发布时明确顺序。
- 对照 `git diff` 复核最终修改，并运行与范围相称的验证。
- 最终结果必须列明修改范围、架构或根因、实际运行的命令及结果、未运行验证及原因，以及剩余风险。
- 提交保持单一主题，并使用简短、明确的中文祈使句。
