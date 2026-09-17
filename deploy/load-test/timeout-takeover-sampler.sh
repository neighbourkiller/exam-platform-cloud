#!/usr/bin/env bash
set -Eeuo pipefail

# 接管观测专用采样器。stats 和 registry 分别运行在独立进程组中，
# 由 observe-timeout-node-crash.sh 在停止后 wait，避免跨轮残留查询。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-exam-platform-cloud-timeout}"
MODE="${1:?mode is required: stats|registry}"
OUTPUT_FILE="${2:?output file is required}"
EXAM_ID="${3:?exam id is required}"

if ! [[ "$EXAM_ID" =~ ^[0-9]+$ ]]; then
  echo "EXAM_ID 必须是数字" >&2
  exit 2
fi

mkdir -p "$(dirname "$OUTPUT_FILE")"
umask 077
: > "$OUTPUT_FILE"

compose() {
  docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
    -f "$PROJECT_ROOT/docker-compose.yml" \
    -f "$SCRIPT_DIR/compose.timeout-test.yaml" "$@"
}

sql_run() {
  local schema="$1"
  local sql="$2"
  compose exec -T mysql sh -c \
    'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N "$1" -e "$2"' \
    _ "$schema" "$sql"
}

sample_stats() {
  local stats
  stats="$(sql_run exam_runtime "
    select coalesce(sum(status='PENDING'),0), coalesce(sum(status='PROCESSING'),0),
           coalesce(sum(status='DONE'),0), coalesce(sum(status='FAILED'),0),
           coalesce(sum(attempt_count>1),0),
           (select count(*) from submission_final_payload fp
              join submission s on s.id=fp.submission_id where s.exam_id=$EXAM_ID)
      from submission_timeout_task where exam_id=$EXAM_ID;" 2>/dev/null || echo NA)"
  printf '%s\t%s\n' "$(date --iso-8601=seconds)" "$(echo "$stats" | tr '\t' ',')" >> "$OUTPUT_FILE"
}

sample_registry() {
  local registry
  registry="$(sql_run xxl_job \
    "select count(*) from xxl_job_registry where registry_key=0x6578616d2d72756e74696d652d6578656375746f72;" \
    2>/dev/null || echo NA)"
  registry="$(printf '%s' "$registry" | tr -d '[:space:]')"
  printf '%s\t%s\t%s\n' "$(date --iso-8601=seconds)" "$(date +%s)" "$registry" >> "$OUTPUT_FILE"
}

case "$MODE" in
  stats)
    while true; do
      sample_stats
      sleep "${TAKEOVER_STATS_SAMPLE_INTERVAL_SECONDS:-2}"
    done
    ;;
  registry)
    while true; do
      sample_registry
      sleep "${TAKEOVER_REGISTRY_SAMPLE_INTERVAL_SECONDS:-2}"
    done
    ;;
  *)
    echo "未知采样模式: $MODE" >&2
    exit 2
    ;;
esac
