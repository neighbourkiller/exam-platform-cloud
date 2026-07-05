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
- **Grading Engine** - Automated grading for objective questions; subjective answers are routed to a manual grading queue with batch-scoring support.
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
│   └── exam-common-security/          # Shared Spring Security and JWT authentication mechanisms
├── apis/                              # Service Feign Client APIs and Shared DTOs
│   ├── exam-iam-api/
│   ├── exam-academic-api/
│   ├── exam-content-api/
│   ├── exam-management-api/
│   └── exam-runtime-api/
├── services/                          # Microservice Applications
│   ├── exam-gateway/                  # API Gateway (Route routing, CORS, rate limiting) - Ports: 16730
│   ├── exam-iam-service/              # Identity and Access Management (Auth & Users)
│   ├── exam-academic-service/         # Academic management (Courses, Classes)
│   ├── exam-content-service/          # Question bank and exam paper service
│   ├── exam-management-service/       # Exam arrangements and proctoring
│   ├── exam-runtime-service/          # Exam taking, snapshots, anti-cheat, and submission
│   ├── exam-grading-service/          # Objective auto-grading and manual grading queue
│   └── exam-reporting-service/        # Statistical dashboards and reporting
└── src/main/resources/frontend/       # Vue 3 Frontend Single Page Application
```

---

## Getting Started

### Prerequisites

- **Java 21** (JDK)
- **Node.js 18+** and npm
- **Docker** and **Docker Compose**

### 1. Generate JWT Key Pairs

The authentication service uses asymmetric RS256 JWT tokens. You must generate public/private key pairs before starting the docker services:

On Windows (PowerShell):
```powershell
./deploy/generate-dev-secrets.ps1
```

This generates keys under `deploy/secrets/` which will be mounted to containers via Docker Secrets.

### 2. Start Services via Docker Compose

Run the following command in the project root directory:

```bash
docker compose up -d
```

This starts all infrastructure services and backend microservices:
- **MySQL 8.4** (`:13306`)
- **Redis 7.4** (`:16379`)
- **RabbitMQ 4.1** (`:15673` AMQP, `:15672` Management)
- **MinIO** (`:19000` API, `:19001` Console)
- **Nacos 3.1.1** (`:8848` Console)
- **XXL-Job Admin 3.4.0** (`:18080` Admin console)
- **Gateway & Microservices** (Gateway listening on `:16730`)

> [!IMPORTANT]
> MySQL initialization scripts in `deploy/mysql/init` will automatically create the required databases (`exam_iam`, `exam_academic`, `exam_content`, `exam_management`, `exam_runtime`, `exam_grading`, `exam_reporting`, `nacos_config`, `xxl_job`) and seed Nacoses configs.

### 3. Initialize Nacos Configurations

To push local configuration profiles to Nacos:

On Windows (PowerShell):
```powershell
./deploy/publish-nacos.ps1
```

### 4. Build and Run Backend Services (Optional for Local Debugging)

If you wish to run/debug specific services locally instead of in Docker:

1. Stop the target Docker container (e.g. `docker compose stop iam-service`).
2. Build the project:
   ```bash
   ./mvnw clean package -DskipTests
   ```
3. Run the microservice using your IDE or command line targeting the appropriate service directory.

### 5. Run the Frontend

```bash
cd src/main/resources/frontend
npm install
npm run dev
```

The dev server will be available at **http://localhost:5173**, proxying API requests to the gateway at **http://localhost:16730**.

### 6. Default Accounts

| Account | Password | Role |
|---------|----------|------|
| `admin` | `123456` | Administrator |
| `teacher1` | `123456` | Teacher |
| `student1` | `123456` | Student |

---

## API Documentation

When the system is running, Swagger UI / OpenAPI documentation is aggregated and available at the gateway:

**http://localhost:16730/swagger-ui.html**

## Environment Variables

Microservices retrieve configurations from Nacos. Key bootstrap variables can be configured in `.env`:

| Variable | Default | Description |
|----------|---------|-------------|
| `MYSQL_ROOT_PASSWORD` | - | Root password for MySQL container |
| `EXAM_DB_PASSWORD` | - | Database password for all exam services |
| `NACOS_PASSWORD` | `nacos` | Nacos console password |
| `RABBITMQ_PASSWORD` | - | RabbitMQ connection password |
| `MINIO_ACCESS_KEY` | - | MinIO console access key |
| `MINIO_SECRET_KEY` | - | MinIO console secret key |
| `SERVICE_CLIENT_SECRET` | - | Internal Feign client security token |
| `XXL_JOB_ACCESS_TOKEN` | - | Access token for XXL-Job executor authentication |
