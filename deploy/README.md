# 部署与验证目录

`docker-compose.yml` 是项目唯一正式 Compose 入口。本目录只保存该入口依赖的配置、初始化资源、辅助脚本、测试覆盖文件和运维手册。

| 目录 | 用途 |
| --- | --- |
| `scripts/` | 调用根 Compose 入口的宿主机示例脚本 |
| `runbooks/` | 上线、观察、重放、排障和回退手册 |
| `nacos-config/` | 由 Compose 初始化任务发布的 Nacos 源配置 |
| `mysql/init/`、`redis/` | 容器内部使用的初始化或入口资源 |
| `migration/` | 从旧单体迁移到微服务的离线 SQL |
| `seed-data/` | 演示和初始化数据 |
| `load-test/` | 压测场景、执行工具、测试覆盖文件和本地结果 |
| `wslc/` | 经授权的实验性 WSLC 备用编排 |

压测目录进一步分为 `scenarios/`、`harness/`、`compose/`、`fixtures/` 和 Git 忽略的 `results/`。
