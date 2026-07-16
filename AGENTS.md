# Repository Guidelines

## 协作约定

1. 默认使用中文回复。
2. 在 Windows 上调用 PowerShell 时，优先使用 PowerShell 7：`C:\Program Files\PowerShell\7`。
3. 提交信息使用简短、明确的中文祈使句。
4. 修改前先确认目标位于当前微服务模块；根目录保留的单体代码和迁移不是新功能的默认落点。

## 项目概览

EkuExam Cloud 是一个面向管理员、教师和学生的在线考试平台。当前代码库采用 Maven 多模块微服务架构：网关统一入口，Nacos 负责注册与配置，服务之间通过 OpenFeign 和 RabbitMQ 事件协作；各业务服务使用独立 MySQL Schema。

核心技术栈：

| 层面 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.0.4、Spring Cloud 2025.1.0、Spring Cloud Alibaba 2025.1.0.0 |
| 服务治理 | Nacos 3.1.1、OpenFeign、Spring Cloud Gateway、XXL-Job 3.4.0 |
| 安全 | Spring Security、RSA JWT、服务间身份令牌 |
| 数据 | MySQL 8.4、MyBatis-Plus 3.5.14、Flyway、Redis 7.4 |
| 异步与存储 | RabbitMQ 4.1、Outbox/Inbox、MinIO |
| 前端 | Vue 3.5、Vite 6、Element Plus、Pinia、Axios、ECharts |
| 测试与监控 | JUnit 5、Mockito、Testcontainers、Spring Boot Actuator |

## 项目结构与模块边界

```text
exam-platform-cloud/
├── pom.xml                                  # 聚合 POM：18 个 Maven 模块
├── compose.yaml                              # 当前本地微服务栈的唯一 Compose 入口
├── docker-compose.yml                        # 历史文件；不要与 compose.yaml 混用
├── platform/
│   ├── exam-common-core/                     # 响应模型、异常、通用事件与基础配置
│   ├── exam-common-security/                 # JWT 资源服务器、服务间认证、Feign 异常处理
│   ├── exam-outbox-support/                  # 可靠事件发布、租约、退避、清理与指标
│   ├── exam-audit-support/                   # 审计切面与审计事件
│   └── exam-csv-import-support/              # CSV 导入解析能力
├── apis/                                     # 跨服务 DTO 与 Feign 契约，不放业务实现
│   ├── exam-iam-api/
│   ├── exam-academic-api/
│   ├── exam-content-api/
│   ├── exam-management-api/
│   └── exam-runtime-api/
├── services/
│   ├── exam-gateway/                         # API 网关、路由、跨域、限流与资源服务器
│   ├── exam-iam-service/                     # 登录、令牌、用户、角色与认证限流
│   ├── exam-academic-service/                # 课程、班级、教师与学生档案
│   ├── exam-content-service/                 # 题库、试卷、快照与 MinIO 资源
│   ├── exam-management-service/              # 考试安排、发布、监考策略与处置
│   ├── exam-runtime-service/                 # 开考、答题快照、客户端租约、防作弊与交卷
│   ├── exam-grading-service/                 # 客观题判分、主观题批阅与成绩事件
│   └── exam-reporting-service/               # 成绩投影、统计看板与管理员监控
├── src/main/resources/frontend/              # 当前 Vue 单页应用
│   └── src/{api,composables,layout,router,stores,utils,views}/
├── deploy/
│   ├── start-local.sh                        # WSL 一键准备、构建、启动与初始化
│   ├── prepare-local-deployment.sh           # 生成 .env.microservices 与本地 Docker Secrets
│   ├── publish-nacos.sh                      # 发布 deploy/nacos-config/ 中的配置
│   ├── OUTBOX_OPERATIONS.md                  # Outbox 发布、排障与重放说明
│   └── SNAPSHOT_FLUSH_OPERATIONS.md          # 快照增量落库排障说明
└── src/main/{java,resources/db}/             # 旧单体遗留代码与迁移；前端目录除外
```

### 必须遵守的边界

- 新后端功能应进入对应的 `services/exam-*-service`，不要默认修改根目录 `src/main/java` 的旧单体实现。
- 跨服务调用先在 `apis/` 定义稳定 DTO/契约，再由服务通过 Feign Client 调用；禁止直接读取其他服务数据库。
- 每个服务只维护自己的 Schema、Flyway 迁移和 Outbox 表。跨服务一致性依赖事件与消费者幂等，不依赖分布式事务。
- `platform/` 只放跨服务复用基础能力。业务专属代码不得为了复用而过早下沉。
- `src/main/resources/frontend` 是当前前端工程，仍与微服务网关联调；不要把它误判为旧单体后删除或迁移。

## 本地环境与启动

### 前置条件

- Java 21、Node.js 18+、Docker Desktop（启用 WSL Integration）。
- WSL 一键启动还需要 `docker`、`openssl`、`python3` 与 `python3-bcrypt`。
- 本地机密写入 `.env.microservices` 和 `deploy/secrets/`；二者均被 Git 忽略，禁止提交、打印或复制到文档。

`compose.yaml` 是当前标准入口。它使用独立的端口和数据卷，包含 MySQL、Redis、RabbitMQ、MinIO、Nacos、XXL-Job、网关及全部业务服务。不要直接执行未指定环境文件的 `docker compose`，也不要同时使用 `docker-compose.yml`。

### 一键启动（WSL 推荐）

```bash
bash deploy/start-local.sh
```

脚本会准备机密、构建全部 JAR、启动基础设施、发布 Nacos 配置、启动服务并初始化演示数据。网关地址为 `http://localhost:16730`；前端开发服务器默认使用 `http://localhost:5173`。

### 分阶段启动

```bash
bash deploy/prepare-local-deployment.sh
./mvnw -B clean package -DskipTests
docker compose -p exam-platform-cloud -f compose.yaml --env-file .env.microservices up -d mysql redis rabbitmq minio nacos xxl-job-admin
bash deploy/publish-nacos.sh
docker compose -p exam-platform-cloud -f compose.yaml --env-file .env.microservices up -d --build
```

仅调试某个服务时，先启动依赖基础设施并发布 Nacos 配置；然后在 IDE 中运行该服务的 `*Application`。可用以下命令先构建所需模块及其依赖：

```bash
./mvnw -pl services/exam-runtime-service -am package
```

将上例中的模块路径替换为实际服务路径。不要使用根聚合 POM 执行无目标的 `spring-boot:run`。

### 前端

```bash
cd src/main/resources/frontend
npm ci
npm run dev
```

- `vite.config.js` 使用 5173 端口；开发环境 API 基地址由 `.env.development` 的 `VITE_API_BASE_URL` 指向网关。
- 生产构建使用 `npm run build`，预览使用 `npm run preview`。
- 当前前端未配置 lint 或自动化测试；前端改动至少执行构建并进行受影响角色的手工冒烟验证。

## 构建、测试与验证

```bash
# 全仓单元测试与集成测试
./mvnw test

# 指定服务及其依赖的测试
./mvnw -pl services/exam-runtime-service -am test

# 全仓可部署 JAR
./mvnw -B clean package -DskipTests

# 前端生产构建
cd src/main/resources/frontend
npm run build
```

- 测试位于各模块的 `src/test/java`；优先为 Service、可靠事件和边界校验补测试。
- Runtime 与 Outbox 模块含 Testcontainers 测试。执行前确认 Docker 可用；不要因本地 Docker 不可用而把基础设施测试误判为业务回归。
- 仓库当前没有 CI、前端测试或 lint 配置。变更前后请在 PR/交付说明中明确实际运行过的命令和未运行的原因。
- Spring Boot 警告、Mockito 动态代理提示或 Surefire dumpstream 不等于测试失败；以 Maven 的退出码和测试汇总为准。

## 数据库、消息与可靠性

### Flyway

- 当前服务的迁移位于各自的 `services/exam-*-service/src/main/resources/db/migration/`，按服务独立递增版本。
- 根目录 `src/main/resources/db/migration/` 是旧单体迁移，不应作为微服务数据库变更的默认目录。
- 新 Schema 变更必须新增版本化 Flyway 脚本，不要手工修改已发布迁移，也不要以直接改生产库替代迁移。
- 涉及现有数据的迁移先验证真实列类型、索引和数据，再准备备份与回滚方案。

### Outbox / Inbox

- 写业务数据和写 `outbox_event` 应在同一事务内完成；由 `exam-outbox-support` 的租约式发布器异步投递 RabbitMQ。
- 消费方使用 `inbox_event` 或等价约束去重。可靠性目标是“至少一次投递 + 幂等消费”，不要声称绝对不重复或绝对不丢失。
- 变更共享队列、死信交换机、重试参数或 Outbox 状态机时，检查所有声明同一拓扑的服务，避免 RabbitMQ 参数不一致。
- 六个持有 Outbox 的服务需要协调发布；上线、观察、重放和清理应遵循 [deploy/OUTBOX_OPERATIONS.md](deploy/OUTBOX_OPERATIONS.md)。

### 考试运行时

- 单活答题的真正约束在 Runtime 的服务端客户端租约；浏览器锁或 `BroadcastChannel` 只是辅助体验，不能替代服务端校验。
- 快照采用 Redis 快路径与 MySQL 持久化兜底，包含版本与租约保护。排查积压或失败快照前先阅读 [deploy/SNAPSHOT_FLUSH_OPERATIONS.md](deploy/SNAPSHOT_FLUSH_OPERATIONS.md)。
- 超时交卷由 XXL-Job 触发；不要将其表述为普通 Spring 定时任务。

## 编码约定

### Java

- 使用 4 空格缩进，与所在模块的现有风格保持一致。
- 使用构造器注入；优先 `@RequiredArgsConstructor`，不新增字段注入。
- 包按业务边界组织，通常包含 `controller`、`service`、`dto`、`client`、`messaging`、`repository` 等子包。
- Controller 使用 `ApiResponse`、`PageResponse` 与 `BusinessException` / `GlobalExceptionHandler` 的统一边界；安全约束采用 `@PreAuthorize` 和共享资源服务器配置。
- DTO、Feign 契约和事件模型需要可演进、可序列化；新增跨服务字段时同步检查生产者、消费者和历史消息兼容性。
- 非必要不对无关模块重构。共享队列、迁移和安全配置变更要先定位所有依赖方。

### Vue

- 组件使用 PascalCase；普通 JavaScript 模块保持小写短名称。
- API 请求集中在 `src/api/http.js` 和 `src/api/index.js`；不要在视图中新增分散 Axios 配置。
- Pinia 状态位于 `src/stores/`，复用逻辑位于 `src/composables/` 或 `src/utils/`。
- 页面按角色维护在 `views/admin`、`views/auth`、`views/student`、`views/teacher`；保持 Element Plus、ECharts 和现有全局样式的一致性。

## 安全、配置与排障

- Nacos 配置源文件位于 `deploy/nacos-config/`，修改后执行 `bash deploy/publish-nacos.sh`；不要假设本地 YAML 修改会自动进入运行中的 Nacos。
- 所有密码、JWT 私钥、服务间密钥与容器环境变量必须留在被忽略的环境/Secrets 文件中。日志、错误信息、截图和提交不得包含其值。
- 网关统一对外暴露 API；接口默认前缀为 `/api/v1/`，运行后从网关访问 Swagger UI。
- 健康检查和指标通过各服务的 Actuator 提供。排障时先区分代码状态、Nacos 配置状态、容器状态、Flyway 历史和实时数据库状态。
- Compose 提示存在两个配置文件时，显式传入 `-f compose.yaml --env-file .env.microservices`；不要依赖 Docker 的自动选择。

## 提交与评审

- 提交保持单一主题，中文示例：`修复运行时快照租约续期`、`补充阅卷事件幂等测试`。
- 不提交 `.env.microservices`、`deploy/secrets/`、构建产物、`node_modules` 或本地日志。
- Pull Request / 交付说明应包含：受影响模块、数据库/消息/Nacos 配置变更、兼容性或发布顺序、已执行的测试命令，以及前端手工验证或截图（如适用）。
- 修改文档时以聚合 `pom.xml`、`compose.yaml`、`deploy/` 脚本和当前服务代码为准；不要把旧单体说明重新写回当前微服务文档。
