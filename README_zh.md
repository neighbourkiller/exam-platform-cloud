<div align="center">

# EkuExam Cloud

**微服务在线考试系统 / Cloud-Native Online Exam System**

[![Java](https://img.shields.io/badge/Java-21-blue?style=flat-square)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.4-green?style=flat-square)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2025.1.0-blue?style=flat-square)](https://spring.io/projects/spring-cloud)
[![Vue.js](https://img.shields.io/badge/Vue.js-3.5-brightgreen?style=flat-square)](https://vuejs.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.4-orange?style=flat-square)](https://dev.mysql.com/)

[功能特性](#功能特性) | [技术栈](#技术栈) | [项目架构](#项目架构) | [快速开始](#快速开始) | [接口文档](#接口文档) | [English](README.md)

</div>

---

EkuExam Cloud 是一个基于微服务架构的云原生在线考试与成绩评定平台，支持管理员、教师、学生三种角色。系统涵盖题库管理、智能组卷、考试全生命周期管理、实时防作弊监控、自动阅卷以及多维度的成绩统计与数据分析。

## 功能特性

- **题库管理** - 支持单选、多选、判断、填空、简答五种题型的增删改查，支持通过 MinIO 上传题目图片或附件。
- **智能组卷** - 支持手动组卷以及基于科目、难度、题型约束自动生成试卷。
- **考试全生命周期** - 创建、定时发布、开考、交卷、强制终止，支持按班级分配和定时调度。
- **实时答题快照** - 每 30 秒将脏草稿同步至 Redis，正常每 15 分钟增量落入 MySQL；Redis 故障时同步降级写库，交卷时立即持久化最终答案。
- **防作弊监控** - 包含切屏检测、摄像头抓拍监控、事件日志记录，教师端可进行实时处置与标记。
- **阅卷引擎** - 客观题自动评分；主观题进入批阅队列，支持教师按题批量评分。
- **统计看板** - 成绩分布、班级均分趋势、高频错题分析、学生成绩明细，基于 ECharts 实现多维度可视化。
- **后台管理** - 支持 CSV/Excel 批量导入用户/班级/课程，角色权限映射，操作审计日志记录。

## 技术栈

| 层级 | 技术 |
|------|------|
| **后端核心** | Java 21, Spring Boot 4.0.4, Spring Cloud 2025.1.0, Spring Cloud Alibaba 2025.1.0.0 |
| **网关与安全** | Spring Cloud Gateway, Spring Security, JWT (jjwt 0.12.7) |
| **持久层** | MyBatis-Plus 3.5.14 |
| **注册与配置中心** | Nacos v3.1.1 |
| **分布式调度** | XXL-Job v3.4.0 |
| **前端** | Vue 3.5, Vite 6, Element-Plus 2.9, ECharts 5.6, Pinia 3, Axios |
| **数据与缓存** | MySQL 8.4 (各服务数据库隔离), Redis 7.4 (答题快照、限流) |
| **消息队列** | RabbitMQ 4.1 (异步交卷及判题队列处理) |
| **对象存储** | MinIO (题目图片与附件存储) |

---

## 项目架构

项目重构为基于 Maven 的多模块微服务架构：

```
exam/
├── platform/                          # 公共基础设施模块
│   ├── exam-common-core/              # 核心工具类、基类、全局异常处理及公共配置
│   └── exam-common-security/          # 共享的安全拦截与 JWT 认证校验模块
├── apis/                              # 微服务间 OpenFeign 调用接口定义与 DTO
│   ├── exam-iam-api/
│   ├── exam-academic-api/
│   ├── exam-content-api/
│   ├── exam-management-api/
│   └── exam-runtime-api/
├── services/                          # 微服务应用
│   ├── exam-gateway/                  # API 网关（路由转发、跨域处理、接口限流）- 端口: 16730
│   ├── exam-iam-service/              # 统一身份认证与权限管理服务
│   ├── exam-academic-service/         # 教务管理服务（课程、班级、学生关系）
│   ├── exam-content-service/          # 题库与试卷服务
│   ├── exam-management-service/       # 考试安排与监考服务
│   ├── exam-runtime-service/          # 考试运行时服务（开始考试、答题快照、提交答卷）
│   ├── exam-grading-service/          # 阅卷判题服务（客观题自动判分、主观题人工批改）
│   └── exam-reporting-service/        # 数据分析与统计报表服务
└── src/main/resources/frontend/       # Vue 3 前端单页应用
```

---

## 快速开始

### 环境要求

- **Docker** 与 **Docker Compose**
- 本地调试前端时另需 **Node.js 18+** 和 npm
- 本地调试后端时另需 **Java 21**

### 1. 准备部署环境变量

复制环境变量模板并填写全部必填项，真实密码和密钥不得提交：

```bash
cp .env.microservices.example .env.microservices
```

`NACOS_AUTH_TOKEN` 必须是 Base64 编码且解码后不少于 32 字节；
`APP_DEFAULT_PASSWORD_HASH` 必须是 `APP_DEFAULT_PASSWORD` 对应的、带
`{bcrypt}` 前缀的 Spring Security BCrypt 哈希。

### 2. 使用唯一 Compose 入口部署

```bash
docker compose \
  -p exam-platform-cloud \
  -f docker-compose.yml \
  --env-file .env.microservices \
  up -d --build
```

也可以运行只调用上述命令的示例脚本：

```bash
bash deploy/docker-deploy-example.sh
```

该示例脚本固定部署 4 个 Runtime 实例，网关和其他业务模块各部署 1 个容器实例，
并将全部模块与中间件接入名为 `exam-cloud` 的 Docker 网络。直接执行 Compose 命令时，
Runtime 实例数仍可通过 `APP_RUNTIME_REPLICAS` 调整。

`docker-compose.yml` 是唯一部署入口。Docker 会在多阶段镜像构建中完成 Maven
打包，并由 Compose 一次性服务生成 JWT 密钥、发布 Nacos 配置以及导入演示账号、
5 门课程和 100 道初始题目；宿主机不再运行准备、发布或种子数据脚本。

该命令将启动所有基础设施和后端微服务：
- **MySQL 8.4** (`:23306`)
- **Redis 7.4** (`:26379`)
- **RabbitMQ 4.1** (`:15672` AMQP 协议, `:25672` 管理后台)
- **MinIO** (`:29000` API, `:29001` 控制台)
- **Nacos 3.1.1** (`:18081` 控制台，`:18848` 注册与配置中心)
- **XXL-Job Admin 3.4.0** (`:18080` 调度中心后台)
- **微服务及网关** (网关统一监听 `:16730` 端口)

> [!IMPORTANT]
> MySQL 容器会执行 `deploy/mysql/init`，创建各微服务 Schema、Nacos 与 XXL-Job
> 表和专用账号。`nacos-config-init` 通过 Nacos API 发布 `deploy/nacos-config/`，
> `app-data-init` 在业务 Flyway 完成后导入 `deploy/seed-data/`。这些一次性服务都由
> `docker-compose.yml` 管理。已有 MySQL 数据卷不会重新执行
> `/docker-entrypoint-initdb.d`；需要修改已有环境时必须遵循迁移和运维文档，不能删除
> 数据卷冒充升级。

更新 Nacos 源配置后，用同一 Compose 入口强制重建发布服务：

```bash
docker compose -p exam-platform-cloud -f docker-compose.yml \
  --env-file .env.microservices up --no-deps --force-recreate nacos-config-init
```

### 3. 编译与本地调试（可选）

如果您希望在本地开发环境调试特定微服务，而不是全部运行在 Docker 中：

1. 使用唯一 Compose 文件停止对应容器，例如：
   `docker compose -p exam-platform-cloud -f docker-compose.yml --env-file .env.microservices stop iam-service`。
2. 构建整个 Maven 项目：
   ```bash
   ./mvnw clean package -DskipTests
   ```
3. 在 IDE 中导入项目，启动对应的微服务应用启动类。

### 4. 启动前端

```bash
cd src/main/resources/frontend
npm install
npm run dev
```

启动后可访问 **http://localhost:5173**。前端会将 API 请求统一代理到网关 **http://localhost:16730**。

### 5. 默认登录账户

| 账号 | 密码 | 角色 |
|------|------|------|
| `admin` | `.env.microservices` 中的 `APP_DEFAULT_PASSWORD` | 系统管理员 |
| `teacher01` | `.env.microservices` 中的 `APP_DEFAULT_PASSWORD` | 教师 |
| `20010001` 至 `20010003` | `.env.microservices` 中的 `APP_DEFAULT_PASSWORD` | 学生 |


### 关键设计

- **接口前缀**：所有 API 统一使用 `/api/v1/`
- **认证流程**：访问令牌置于 `Authorization` 请求头，刷新令牌存于 HttpOnly Cookie（`exam_refresh_token`）
- **权限控制**：三种角色（ADMIN / TEACHER / STUDENT），后端通过 `@PreAuthorize` 注解、前端通过路由守卫双重校验
- **数据库版本管理**：Flyway 迁移脚本位于 `db/migration/`（V1-V10），开发环境种子数据位于 `db/dev-seed/`（V1001+）

## 接口文档

后端运行时可访问 Swagger UI：

**http://localhost:16730/swagger-ui.html**

核心接口分组：

| 模块 | 路径 | 说明 |
|------|------|------|
| 认证 | `/api/v1/auth` | 登录、登出、令牌刷新、修改密码 |
| 管理 | `/api/v1/admin` | 用户/角色/课程/班级增删改查、批量导入、审计日志 |
| 考试 | `/api/v1/exams` | 考试全生命周期、监考、学生答卷 |
| 题库 | `/api/v1/questions` | 题目管理、图片上传 |
| 试卷 | `/api/v1/papers` | 试卷创建、自动组卷 |
| 阅卷 | `/api/v1/grading` | 待批阅列表、批量评分 |
| 统计 | `/api/v1/analytics` | 成绩统计、趋势分析、错题分析 |
| 班级 | `/api/v1/teacher/classes` | 教师班级与学生管理 |

## 环境变量

Compose 启动变量保存在未提交的 `.env.microservices` 中，主要必填项如下：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `MYSQL_ROOT_PASSWORD` | 无 | MySQL root 密码 |
| `EXAM_DB_PASSWORD` | 无 | 各微服务及 Nacos、XXL-Job 专用账号密码 |
| `NACOS_PASSWORD` | 无 | Nacos 管理员密码 |
| `NACOS_AUTH_TOKEN` | 无 | Nacos 鉴权 Token |
| `RABBITMQ_PASSWORD` | 无 | RabbitMQ 账号密码 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 无 | MinIO 管理凭据 |
| `SERVICE_CLIENT_SECRET` | 无 | 服务间调用密钥 |
| `XXL_JOB_ACCESS_TOKEN` | 无 | XXL-Job 调度令牌 |
| `APP_DEFAULT_PASSWORD` | 无 | 演示账号及新用户默认密码 |
| `APP_DEFAULT_PASSWORD_HASH` | 无 | 与默认密码对应的 BCrypt 哈希 |

## 运行测试

```bash
# 运行全部后端测试
./mvnw test

# 运行指定测试类
./mvnw test -Dtest=ExamServiceTest
```

测试框架使用 JUnit 5、Mockito 和 Spring Boot Test，包含 `spring-boot-starter-webmvc-test` 和 `mybatis-spring-boot-starter-test`。
