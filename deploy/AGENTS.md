# Deployment Guidelines

本文件适用于 `deploy/` 下的启动脚本、Nacos 配置、Secrets 准备和运维文档，并与仓库根 `AGENTS.md` 共同生效。

## Compose 与启动

- `docker-compose.yml` 是唯一 Compose 入口，负责中间件、初始化配置、初始数据与微服务容器编排；禁止新增或恢复其他 Compose 部署入口。
- 调用 Compose 时显式使用 `docker compose -p exam-platform-cloud -f docker-compose.yml --env-file .env.microservices ...`，不要依赖自动选择配置文件。
- 宿主机部署脚本只允许作为调用 `docker-compose.yml` 的示例，不得生成配置、发布 Nacos、写数据库、单独启动中间件或在宿主机构建 JAR。
- `deploy/wslc/` 是经项目维护者明确授权的实验性备用编排例外，仅用于 WSLC Public Preview 验证；它不得替代、反向生成或分叉修改正式的 `docker-compose.yml`，其差异和回滚方式必须在目录内文档中维护。
- `deploy/mysql/init/` 与 `deploy/redis/` 下的脚本是由中间件容器内部调用的初始化或入口逻辑，不是宿主机部署入口。
- 不在本文件复述完整启动步骤、端口或基础设施版本；修改部署流程时，仅更新 README 和专用运维文档中受影响的说明。

## Nacos 与机密

- Nacos 配置源文件位于 `deploy/nacos-config/`；`docker-compose.yml` 中的 `nacos-config-init` 一次性服务负责通过 Nacos API 幂等发布，不能假设只修改仓库文件就会改变运行中配置。
- `.env.microservices` 只保存本地机密且保持 Git 忽略；JWT 密钥由 `jwt-key-init` 写入 Compose 命名卷。禁止提交、打印、写入文档或复制任何真实值。
- 脚本、日志、截图和错误信息不得泄露密码、JWT 私钥、服务间密钥或容器环境变量。

## 运维与排障

- Outbox 上线、观察、重放和清理遵循 [`OUTBOX_OPERATIONS.md`](OUTBOX_OPERATIONS.md)；快照增量落库排障遵循 [`SNAPSHOT_FLUSH_OPERATIONS.md`](SNAPSHOT_FLUSH_OPERATIONS.md)。
- 排障时区分仓库代码、Nacos 已发布配置、容器状态、Flyway 历史和实时数据库状态，不用某一层的状态替代其他层证据。
- 变更共享配置、消息拓扑、Schema 初始化或服务启动顺序时，说明兼容性、依赖关系、发布顺序和回滚方案。
