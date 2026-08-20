#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export APP_RUNTIME_REPLICAS=4

exec docker compose \
    -p exam-platform-cloud \
    -f "$PROJECT_ROOT/docker-compose.yml" \
    --env-file "$PROJECT_ROOT/.env.microservices" \
    up -d --build
