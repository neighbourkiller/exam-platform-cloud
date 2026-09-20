<div align="center">

# EkuExam Cloud

**微服务在线考试系统 / Cloud-Native Online Exam System**

[![Java](https://img.shields.io/badge/Java-21-blue?style=flat-square)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.4-green?style=flat-square)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2025.1.0-blue?style=flat-square)](https://spring.io/projects/spring-cloud)
[![Vue.js](https://img.shields.io/badge/Vue.js-3.5-brightgreen?style=flat-square)](https://vuejs.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.4-orange?style=flat-square)](https://dev.mysql.com/)

[Features](#features) | [Tech Stack](#tech-stack) | [Architecture](#architecture) | [Getting Started](#getting-started) | [API Docs](#api-documentation) | [中文](README_zh.md)

</div>

---

EkuExam Cloud is a cloud-native, microservice-based online exam and grading platform with role-based access control (Admin / Teacher / Student). It covers the complete lifecycle of exams, including question bank management, intelligent paper assembly, real-time proctoring with anti-cheating, auto-grading, and comprehensive performance analytics.

## Features

- **Question Bank** - CRUD for 5 question types (single-choice, multi-choice, true/false, fill-in-the-blank, short-answer) with media upload via MinIO.
- **Smart Paper Assembly** - Manual selection or rule-based auto-generation constrained by subject, difficulty, and question type.
- **Exam Lifecycle** - Create, schedule, publish, start, submit, and terminate exams with target class control.
- **Real-time Answer Snapshots** - Saves student progress every 30 seconds to Redis, persisting to MySQL on submission or session expiry.
- **Anti-cheating Proctoring** - Tab-switch detection, automated webcam screenshot evidence uploads, activity logging, and teacher-side disposition tools.
- **Grading Engine** - Automated grading for objective questions; subjective answers are routed to a manual grading queue with batch-scoring support. Finished exams support versioned answer corrections, resumable regrading, and historical answer restoration; see [regrading operations](deploy/runbooks/REGRADE_OPERATIONS.md).
- **Analytics Dashboard** - Score distribution, class performance trends, wrong-answer ratios, and per-student score breakdowns visualized with ECharts.
- **Admin Management** - Bulk import of users/classes/courses via CSV/Excel, role mapping, and detailed operation audit logging.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 21, Spring Boot 4.0.4, Spring Cloud 2025.1.0, Spring Cloud Alibaba 2025.1.0.0 |
| **Gateway & Security** | Spring Cloud Gateway, Spring Security, JWT (jjwt 0.12.7) |
| **ORM** | MyBatis-Plus 3.5.14 |
| **Registry & Config** | Nacos v3.1.1 |
| **Job Scheduling** | XXL-Job v3.4.0 |
| **Frontend** | Vue 3.5, Vite 6, Element-Plus 2.9, ECharts 5.6, Pinia 3, Axios |
| **Data & Cache** | MySQL 8.4 (Data isolation per service), Redis 7.4 (Snapshots, rate limiting) |
| **Message Queue** | RabbitMQ 4.1 (Asynchronous exam submission processing) |
| **Object Storage** | MinIO (Question images) |

---

## Architecture

The project has been refactored into a Maven multi-module microservice architecture:

```
exam/
├── platform/                          # Common Infrastructure Modules
│   ├── exam-common-core/              # Common utilities, base entities, exceptions, and global configurations
│   ├── exam-common-security/          # Shared Spring Security and JWT authentication mechanisms
│   ├── exam-outbox-support/           # Transactional Outbox infrastructure
│   ├── exam-audit-support/            # Shared operation auditing
│   └── exam-csv-import-support/       # Shared CSV import support
├── apis/                              # Service Feign Client APIs and Shared DTOs
│   ├── exam-iam-api/
│   ├── exam-academic-api/
│   ├── exam-content-api/
│   ├── exam-management-api/
│   ├── exam-runtime-api/
│   └── exam-grading-api/
├── services/                          # Microservice Applications
│   ├── exam-gateway/                  # API Gateway (Route routing, CORS, rate limiting) - Ports: 16730
│   ├── exam-iam-service/              # Identity and Access Management (Auth & Users)
│   ├── exam-academic-service/         # Academic management (Courses, Classes)
│   ├── exam-content-service/          # Question bank and exam paper service
│   ├── exam-management-service/       # Exam arrangements and proctoring
│   ├── exam-runtime-service/          # Exam taking, snapshots, anti-cheat, and submission
│   ├── exam-grading-service/          # Objective auto-grading and manual grading queue
│   └── exam-reporting-service/        # Statistical dashboards and reporting
├── frontend/                          # Vue 3 Frontend Single Page Application
├── deploy/                            # Configuration, scripts, load tests, and runbooks
│   ├── scripts/
│   ├── runbooks/
│   └── load-test/
├── documents/                         # Technical documentation and acceptance records
└── legacy/monolith/                   # Archived monolith; excluded from the Maven reactor
```

---

## Getting Started

### Prerequisites

- **Docker** and **Docker Compose**
- **Java 21** only for local backend debugging
- **Node.js 18+** and npm only for local frontend debugging

### 1. Prepare Deployment Variables

Copy `.env.microservices.example` to `.env.microservices`, fill every required value, and
never commit real credentials. `NACOS_AUTH_TOKEN` must decode to at least 32 bytes and
`APP_DEFAULT_PASSWORD_HASH` must be the Spring Security BCrypt hash (including the
`{bcrypt}` prefix) for `APP_DEFAULT_PASSWORD`.

### 2. Deploy through the Only Compose Entry Point

```bash
docker compose -p exam-platform-cloud -f docker-compose.yml \
  --env-file .env.microservices up -d --build
```

The example wrapper `bash deploy/scripts/docker-deploy-example.sh` only invokes this Compose file.
On Windows with PowerShell 7, invoke the same Linux deployment entry point through WSL:

```powershell
pwsh -File .\deploy\docker-deploy-wsl.ps1
```

To use a non-default WSL distribution, pass its registered name, for example:

```powershell
pwsh -File .\deploy\docker-deploy-wsl.ps1 -Distribution Ubuntu
```

The selected WSL distribution must provide `docker compose` and access to a Docker Engine.
The PowerShell wrapper only converts the project path and calls the Linux example script; it
does not maintain a second Compose deployment command.

An explicitly authorized experimental WSLC fallback is documented under
[`deploy/wslc/`](deploy/wslc/README.md). It invokes `wslc.exe` directly, does not replace the
official Compose entry point, and uses data volumes that are entirely separate from Docker.

The multi-stage Docker build packages the Maven services, while Compose-managed one-shot
services generate JWT keys, publish Nacos configuration, and load demo data.
The example wrapper deploys four Runtime instances and one instance of every other gateway
or business module. All application and middleware containers join the Docker network named
`exam-cloud`; direct Compose usage can still override the Runtime count through
`APP_RUNTIME_REPLICAS`.

This starts all infrastructure services and backend microservices:
- **MySQL 8.4** (`:23306`)
- **Redis 7.4** (`:26379`)
- **RabbitMQ 4.1** (`:15672` AMQP, `:25672` Management)
- **MinIO** (`:29000` API, `:29001` Console)
- **Nacos 3.1.1** (`:18081` Console, `:18848` registry/config API)
- **XXL-Job Admin 3.4.0** (`:18080` Admin console)
- **Gateway & Microservices** (Gateway listening on `:16730`)

> [!IMPORTANT]
> MySQL initialization files in `deploy/mysql/init` create the required schemas and
> middleware users. The `nacos-config-init`, `jwt-key-init`, and `app-data-init` one-shot
> services are part of `docker-compose.yml`; no host-side preparation, publication, or seed
> script is required.

### 3. Build and Run Backend Services (Optional for Local Debugging)

If you wish to run/debug specific services locally instead of in Docker:

1. Stop the target container through `docker-compose.yml`.
2. Build the project:
   ```bash
   ./mvnw clean package -DskipTests
   ```
3. Run the microservice using your IDE or command line targeting the appropriate service directory.

### 4. Run the Frontend

```bash
cd frontend
npm install
npm run dev
```

The dev server will be available at **http://localhost:5173**, proxying API requests to the gateway at **http://localhost:16730**.

### 5. Default Accounts

| Account | Password | Role |
|---------|----------|------|
| `admin` | `APP_DEFAULT_PASSWORD` | Administrator |
| `teacher01` | `APP_DEFAULT_PASSWORD` | Teacher |
| `20010001` to `20010003` | `APP_DEFAULT_PASSWORD` | Student |

---

## API Documentation

When the system is running, Swagger UI / OpenAPI documentation is aggregated and available at the gateway:

**http://localhost:16730/swagger-ui.html**

## Environment Variables

Microservices retrieve configurations from Nacos. Key bootstrap variables are supplied through `.env.microservices`:

| Variable | Default | Description |
|----------|---------|-------------|
| `MYSQL_ROOT_PASSWORD` | - | Root password for MySQL container |
| `EXAM_DB_PASSWORD` | - | Database password for all exam services |
| `NACOS_PASSWORD` | - | Nacos console password |
| `RABBITMQ_PASSWORD` | - | RabbitMQ connection password |
| `MINIO_ACCESS_KEY` | - | MinIO console access key |
| `MINIO_SECRET_KEY` | - | MinIO console secret key |
| `SERVICE_CLIENT_SECRET` | - | Internal Feign client security token |
| `XXL_JOB_ACCESS_TOKEN` | - | Access token for XXL-Job executor authentication |
| `APP_DEFAULT_PASSWORD` | - | Demo-account and new-user default password |
| `APP_DEFAULT_PASSWORD_HASH` | - | Matching Spring Security BCrypt hash for seed data |
