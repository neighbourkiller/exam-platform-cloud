#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env.microservices"
PROJECT_NAME="exam-platform-cloud"
COMPOSE=(docker compose -p "$PROJECT_NAME" -f "$PROJECT_ROOT/compose.yaml" --env-file "$ENV_FILE")

if ! docker info >/dev/null 2>&1; then
    echo "Docker 服务不可用。请先启用 Docker Desktop 的 WSL Integration，或启动 Docker daemon。" >&2
    exit 1
fi

for port in 23306 26379 15672 25672 29000 29001 18081 18848 19848 19849 18080 16730; do
    while IFS= read -r container_id; do
        [[ -n "$container_id" ]] || continue
        container_project="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container_id")"
        if [[ "$container_project" != "$PROJECT_NAME" ]]; then
            container_name="$(docker inspect --format '{{.Name}}' "$container_id")"
            echo "端口 $port 已被 $container_name 占用。请先释放该端口，或调整 compose.yaml 后重试。" >&2
            exit 1
        fi
    done < <(docker ps --filter "publish=$port" --format '{{.ID}}')
done

"$PROJECT_ROOT/deploy/prepare-local-deployment.sh" "$ENV_FILE"

if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
    echo "正在构建全部微服务 JAR..."
    bash "$PROJECT_ROOT/mvnw" -B clean package -DskipTests
fi

echo "正在启动基础设施服务..."
"${COMPOSE[@]}" up -d mysql redis rabbitmq minio nacos xxl-job-admin

echo "正在发布 Nacos 配置..."
ENV_FILE="$ENV_FILE" "$PROJECT_ROOT/deploy/publish-nacos.sh"

echo "正在启动网关和业务服务..."
"${COMPOSE[@]}" up -d --build gateway iam-service academic-service content-service management-service runtime-service grading-service reporting-service

echo "正在初始化本地演示账号..."
ENV_FILE="$ENV_FILE" "$PROJECT_ROOT/deploy/seed-local-data.sh"

echo
"${COMPOSE[@]}" ps
echo "服务已提交启动：网关 http://localhost:16730，Nacos http://localhost:18081/nacos，XXL-Job http://localhost:18080/xxl-job-admin"
