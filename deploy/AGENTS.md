# Deployment Guidelines

本文件适用于 `deploy/` 下的启动脚本、Nacos 配置、Secrets 准备和运维文档，并与仓库根 `AGENTS.md` 共同生效。

## Compose 与启动

- `compose.yaml` 是当前本地微服务栈的唯一标准入口；`docker-compose.yml` 是历史文件，不得混用。
- 调用 Compose 时显式使用 `docker compose -p exam-platform-cloud -f compose.yaml --env-file .env.microservices ...`，不要依赖自动选择配置文件。
- WSL 一键启动入口是 `bash deploy/start-local.sh`；分阶段操作以当前 `prepare-local-deployment`、`publish-nacos` 脚本及其参数为准。
- 不在本文件复述完整启动步骤、端口或基础设施版本；修改部署流程时同步更新对应脚本和专用操作文档。

## Nacos 与机密

- Nacos 配置源文件位于 `deploy/nacos-config/`；修改后必须通过当前发布脚本发布，不能假设仓库文件会自动进入运行中的 Nacos。
- `.env.microservices` 和 `deploy/secrets/` 只保存本地机密且保持 Git 忽略；禁止提交、打印、写入文档或复制其值。
- 脚本、日志、截图和错误信息不得泄露密码、JWT 私钥、服务间密钥或容器环境变量。

## 运维与排障

- Outbox 上线、观察、重放和清理遵循 [`OUTBOX_OPERATIONS.md`](OUTBOX_OPERATIONS.md)；快照增量落库排障遵循 [`SNAPSHOT_FLUSH_OPERATIONS.md`](SNAPSHOT_FLUSH_OPERATIONS.md)。
- 排障时区分仓库代码、Nacos 已发布配置、容器状态、Flyway 历史和实时数据库状态，不用某一层的状态替代其他层证据。
- 变更共享配置、消息拓扑、Schema 初始化或服务启动顺序时，说明兼容性、依赖关系、发布顺序和回滚方案。
