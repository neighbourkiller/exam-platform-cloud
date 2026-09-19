# WSLC 备用部署

本目录提供基于 Windows WSL Containers CLI（`wslc.exe`）的实验性备用部署。
它由项目维护者明确授权作为第二套编排保存在 `deploy/wslc/`，但不替代根目录
[`docker-compose.yml`](../../docker-compose.yml)；日常开发、测试和正式部署仍优先使用
Compose。

## 适用范围

- Windows 11，WSL 版本不低于 2.9.3，并且能够执行 `wslc version`。
- 本地验证 WSLC 原生容器能力，或 Docker Compose 暂时不可用时进行备用部署。
- 不用于生产环境，也不提供容器异常退出后的自动重启保障。

WSLC 当前处于 Public Preview。微软的入门文档只提供容器、镜像、网络和卷等
原语，当前 CLI 没有 Compose 子命令，也没有可供 Docker Compose 连接的 Docker
Engine 兼容端点：

- [Get started with containers on WSL](https://learn.microsoft.com/windows/wsl/tutorials/wsl-containers)
- [WSL Containers 概览](https://learn.microsoft.com/windows/wsl/wsl-container)
- [Docker Engine API 兼容端点跟踪](https://github.com/microsoft/WSL/issues/40976)

## 与 Compose 的关系

[`deploy.ps1`](deploy.ps1) 会从当前 `docker-compose.yml` 动态读取：

- MySQL、Redis、RabbitMQ、MinIO/Silo、Nacos、XXL-Job 等基础镜像；
- 8 个业务服务的 `JAR_FILE` 构建参数；
- `nacos-config-init`、`jwt-key-init` 和 `app-data-init` 的多行命令。

以下内容因 WSLC 没有 Compose 支持，仍在脚本中显式映射：

- 主机端口与网络别名；
- 环境变量组合；
- 命名卷容量和挂载位置；
- 服务启动顺序、健康等待和 Runtime 副本数。

修改 `docker-compose.yml` 的端口、卷、环境变量或依赖关系后，必须同步检查本脚本。

## 前置准备

1. 检查 WSL 和 WSLC：

   ```powershell
   wsl --version
   wslc version
   wslc run --rm hello-world
   ```

2. 在项目根目录准备 `.env.microservices`：

   ```powershell
   Copy-Item .env.microservices.example .env.microservices
   ```

   填写全部必填值，但不要提交或打印该文件。

3. 停止占用相同端口的 Compose 环境：

   ```powershell
   docker compose `
     -p exam-platform-cloud `
     -f .\docker-compose.yml `
     --env-file .\.env.microservices `
     down
   ```

   WSLC 和 Docker Compose 不能同时绑定本项目的同一组宿主机端口。

4. 在不创建容器、网络或卷的情况下检查版本、环境文件和 Compose 映射：

   ```powershell
   pwsh -File .\deploy\wslc\deploy.ps1 -Action Validate
   ```

## 部署

在项目根目录使用 PowerShell 7：

```powershell
pwsh -File .\deploy\wslc\deploy.ps1 -Action Up
```

脚本默认使用 `.env.microservices` 中的 `APP_RUNTIME_REPLICAS`，未配置时创建 4 个
Runtime 实例。也可以显式覆盖：

```powershell
pwsh -File .\deploy\wslc\deploy.ps1 `
  -Action Up `
  -RuntimeReplicas 2
```

使用指定 WSLC Session：

```powershell
pwsh -File .\deploy\wslc\deploy.ps1 `
  -Action Up `
  -Session exam-dev
```

已有基础镜像和业务镜像时，可以跳过拉取或构建：

```powershell
pwsh -File .\deploy\wslc\deploy.ps1 `
  -Action Up `
  -SkipPull `
  -SkipBuild
```

`Up` 会重建同名容器，但保留 WSLC 命名卷。业务镜像使用当前项目源码和根目录
`Dockerfile` 构建，第一次构建耗时较长。

## 状态与日志

查看当前项目容器：

```powershell
pwsh -File .\deploy\wslc\deploy.ps1 -Action Status
```

查看指定容器日志：

```powershell
wslc logs --tail 200 exam-platform-cloud-wslc-gateway
wslc logs --follow exam-platform-cloud-wslc-runtime-service-1
```

使用了 `-Session` 时，直接调用 WSLC 也需要传入相同 Session：

```powershell
wslc --session exam-dev logs --tail 200 exam-platform-cloud-wslc-gateway
```

## 停止、清理与回滚

删除容器和项目网络、保留数据卷：

```powershell
pwsh -File .\deploy\wslc\deploy.ps1 -Action Down
```

永久删除 WSLC 环境的 MySQL、Redis、RabbitMQ、对象存储和 JWT 密钥卷：

```powershell
pwsh -File .\deploy\wslc\deploy.ps1 `
  -Action Down `
  -RemoveVolumes
```

> [!CAUTION]
> `-RemoveVolumes` 会不可恢复地删除 WSLC 环境数据。WSLC 卷和 Docker 命名卷完全
> 独立，该操作不会清理 Docker 卷，也不能作为数据迁移手段。

回滚到正式 Compose 入口时，先执行不带 `-RemoveVolumes` 的 `Down`，再运行：

```powershell
pwsh -File .\deploy\docker-deploy-wsl.ps1
```

Compose 不会读取 WSLC 卷中的数据。如果需要迁移业务数据，应使用数据库导出导入和
对象存储复制流程，不要直接复用 VHD 文件。

## 数据卷

脚本创建独立的 VHD 命名卷：

| 用途 | 默认容量 |
| --- | ---: |
| MySQL | 20 GiB |
| Redis | 4 GiB |
| RabbitMQ | 8 GiB |
| MinIO/Silo | 20 GiB |
| JWT 密钥 | 512 MiB |

当前 WSLC VHD 卷创建需要显式提供 `SizeBytes`。需要调整容量时，先备份数据，再修改
脚本中的卷容量；已有卷不会因脚本值变化而自动扩容。

## 安全说明

- 脚本不会输出环境变量的值。
- 容器环境通过系统临时目录中的短期 env 文件传入，并在脚本结束时删除。
- 运行期间，同一台机器上具有相应文件权限的进程仍可能读取临时文件。
- `.env.microservices`、JWT 私钥、数据库密码和服务间密钥不得提交或复制到日志。

## 已知限制与排障

### 镜像拉取超时

`TLS handshake timeout` 表示 WSLC 到镜像仓库的 TLS 连接超时，不能据此判断标签
不存在。先分别检查网络和镜像：

```powershell
wslc pull pgsty/minio:RELEASE.2026-08-04T00-00-00Z
wslc pull curlimages/curl:8.16.0
wslc pull alpine:3.22
```

成功缓存后，可以使用 `-SkipPull` 重试部署。若返回 `manifest unknown`，才说明当前
仓库中没有对应标签。

### 端口占用

若出现端口绑定失败，检查 Docker Compose、其他 WSLC Session 或本机程序是否已经
占用 `16730`、`18080`、`18081`、`18848`、`19848`、`19849`、`23306`、
`26379`、`29000`、`29001`、`15672`、`25672`。

### 自动重启

WSLC 当前没有 `restart: unless-stopped` 的等价参数。Windows 或 WSLC 重启后，先用
`-Action Status` 检查，再重新执行 `-Action Up`；脚本会重建容器并复用命名卷。

### Testcontainers

WSLC 当前不能作为 Docker Engine API 的透明替代，因此项目中的 Testcontainers 测试
仍需要 Docker 兼容 API；本备用部署脚本不会改变测试运行时。
