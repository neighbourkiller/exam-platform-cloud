#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"
COMPOSE_PROJECT="exam-platform-cloud-timeout"

SCENARIO="${1:-A}"
REPLICAS="${2:-4}"
WORKERS="${3:-8}"
MYSQL_MAX_CONN="${4:-300}"
USERS="${USERS:-10000}"
EXAM_ID="${EXAM_ID:-99000001}"
DUE_SECONDS="${DUE_SECONDS:-60}"
ENABLE_POLLING="${ENABLE_POLLING:-true}"
FAULT_INJECTION="${FAULT_INJECTION:-none}"
FAULT_WAIT_TIMEOUT_SECONDS="${FAULT_WAIT_TIMEOUT_SECONDS:-20}"
BUILD_RUNTIME_IMAGE="${BUILD_RUNTIME_IMAGE:-true}"
POLL_BASE_MS="${POLL_BASE_MS:-10000}"
POLL_MAX_MS="${POLL_MAX_MS:-10000}"
LOAD_FLOW="${LOAD_FLOW:-status_only}"
DB_P99_GATE_SECONDS="${DB_P99_GATE_SECONDS:-30}"
DB_MAX_GATE_SECONDS="${DB_MAX_GATE_SECONDS:-60}"
RUNTIME_DB_POOL_SIZE="${APP_RUNTIME_DB_MAX_POOL_SIZE:-24}"
FAULT_PAUSED_CONTAINER_IDS=()
FAULT_TARGET_PAUSED=false
RESOURCE_SAMPLER_PID=""
RUN_STARTED_AT="$(date --iso-8601=seconds)"

if [ "$FAULT_INJECTION" = "node_crash" ] && [ "$ENABLE_POLLING" = "true" ]; then
  echo "node_crash 场景要求 ENABLE_POLLING=false，避免同步 k6 阻塞故障注入时序" >&2
  exit 2
fi
if ! [[ "$DB_P99_GATE_SECONDS" =~ ^[0-9]+([.][0-9]+)?$ ]] \
  || ! [[ "$DB_MAX_GATE_SECONDS" =~ ^[0-9]+([.][0-9]+)?$ ]] \
  || (( $(echo "$DB_P99_GATE_SECONDS <= 0 || $DB_MAX_GATE_SECONDS <= 0 || $DB_MAX_GATE_SECONDS <= $DB_P99_GATE_SECONDS" | bc -l 2>/dev/null || echo 1) )); then
  echo "数据库门槛必须为正数，且 DB_MAX_GATE_SECONDS 必须大于 DB_P99_GATE_SECONDS" >&2
  exit 2
fi

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
RESULT_DIR="$SCRIPT_DIR/results/${TIMESTAMP}-${SCENARIO}"
mkdir -p "$RESULT_DIR"
exec > >(tee "$RESULT_DIR/run.log") 2>&1

TOKEN_FILE="$SCRIPT_DIR/tokens-$EXAM_ID.json"
RUNTIME_IMAGE_CONTEXT_DIR="$SCRIPT_DIR/.runtime-image"

cleanup() {
  if [ -n "$RESOURCE_SAMPLER_PID" ]; then
    kill "$RESOURCE_SAMPLER_PID" >/dev/null 2>&1 || true
    wait "$RESOURCE_SAMPLER_PID" 2>/dev/null || true
  fi
  rm -f "$TOKEN_FILE" || true
  rm -f "$RUNTIME_IMAGE_CONTEXT_DIR/app.jar" || true
  rmdir "$RUNTIME_IMAGE_CONTEXT_DIR" >/dev/null 2>&1 || true
  if [ "$FAULT_TARGET_PAUSED" = "true" ] && [ -n "${TARGET_CONTAINER_ID:-}" ]; then
    docker unpause "$TARGET_CONTAINER_ID" >/dev/null 2>&1 || true
  fi
  for container_id in "${FAULT_PAUSED_CONTAINER_IDS[@]}"; do
    docker unpause "$container_id" >/dev/null 2>&1 || true
  done
  if [ "$FAULT_INJECTION" = "node_crash" ]; then
    docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
      -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
      up -d --scale runtime-service="$REPLICAS" runtime-service >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

echo "================================================================"
echo "  执行超时交卷压测场景: $SCENARIO"
echo "  结果保存目录: $RESULT_DIR"
echo "  Runtime 副本数: $REPLICAS, 工作者数: $WORKERS"
echo "  MySQL max_connections: $MYSQL_MAX_CONN, 用户规模: $USERS"
echo "  Runtime 单实例数据库连接池: $RUNTIME_DB_POOL_SIZE"
echo "  到期时间偏移: ${DUE_SECONDS}s, 客户端轮询: $ENABLE_POLLING"
echo "  数据库门槛: P99 < ${DB_P99_GATE_SECONDS}s, max < ${DB_MAX_GATE_SECONDS}s"
echo "================================================================"

export TIMEOUT_TEST_MYSQL_MAX_CONNECTIONS="$MYSQL_MAX_CONN"
export APP_TIMEOUT_SUBMISSION_WORKER_COUNT="$WORKERS"
export APP_TIMEOUT_SUBMISSION_BATCH_SIZE="$WORKERS"

# 1. 动态更新/拉起 Compose 容器
if [ "$BUILD_RUNTIME_IMAGE" = "true" ]; then
  JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  JAVA_MAJOR=$("$JAVA_BIN" -version 2>&1 | awk -F '[\".]' '/version/ {print $2; exit}')
  if [ "$JAVA_MAJOR" != "21" ]; then
    echo "压测镜像必须由 JDK 21 构建；请设置 JAVA_HOME 指向 JDK 21（当前 major=$JAVA_MAJOR）" >&2
    exit 2
  fi
  echo "[$(date +'%T')] 使用 JDK 21 打包当前工作区 Runtime..."
  JAVA_HOME="${JAVA_HOME:-}" bash "$PROJECT_ROOT/mvnw" \
    -pl services/exam-runtime-service -am package -DskipTests \
    | tee "$RESULT_DIR/runtime-package.log"

  mkdir -p "$RUNTIME_IMAGE_CONTEXT_DIR"
  cp "$PROJECT_ROOT/services/exam-runtime-service/target/exam-runtime-service-1.0.0-SNAPSHOT.jar" \
    "$RUNTIME_IMAGE_CONTEXT_DIR/app.jar"

  echo "[$(date +'%T')] 构建当前工作区 Runtime 镜像..."
  docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
    -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
    build --progress plain runtime-service | tee "$RESULT_DIR/runtime-image-build.log"
  rm -f "$RUNTIME_IMAGE_CONTEXT_DIR/app.jar"
  rmdir "$RUNTIME_IMAGE_CONTEXT_DIR" >/dev/null 2>&1 || true
else
  echo "[$(date +'%T')] 复用显式指定的现有 Runtime 镜像（BUILD_RUNTIME_IMAGE=false）"
fi

echo "[$(date +'%T')] 编排与启动容器集群 (runtime-service=$REPLICAS)..."
docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
  up -d --scale runtime-service="$REPLICAS" \
  mysql redis rabbitmq minio nacos nacos-config-init jwt-key-init xxl-job-admin iam-service gateway runtime-service

# 2. 收集镜像摘要与 Git 信息
GIT_COMMIT="$(git -C "$PROJECT_ROOT" rev-parse HEAD 2>/dev/null || echo 'UNKNOWN')"
GIT_DIRTY=false
if [ -n "$(git -C "$PROJECT_ROOT" status --porcelain)" ]; then
  GIT_DIRTY=true
fi
RUNTIME_IMAGE_ID=$(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
  images -q runtime-service | head -n 1)
cat << METADATA > "$RESULT_DIR/metadata.json"
{
  "scenario": "$SCENARIO",
  "timestamp": "$TIMESTAMP",
  "gitCommit": "$GIT_COMMIT",
  "gitDirty": $GIT_DIRTY,
  "runtimeImageId": "$RUNTIME_IMAGE_ID",
  "runtimeImageBuiltByThisRun": $BUILD_RUNTIME_IMAGE,
  "replicas": $REPLICAS,
  "workersPerReplica": $WORKERS,
  "mysqlMaxConnections": $MYSQL_MAX_CONN,
  "runtimeDbMaxPoolSize": $RUNTIME_DB_POOL_SIZE,
  "users": $USERS,
  "examId": $EXAM_ID,
  "dueSeconds": $DUE_SECONDS,
  "enablePolling": $ENABLE_POLLING,
  "pollBaseMs": $POLL_BASE_MS,
  "pollMaxMs": $POLL_MAX_MS,
  "loadFlow": "$LOAD_FLOW",
  "dbP99GateSeconds": $DB_P99_GATE_SECONDS,
  "dbMaxGateSeconds": $DB_MAX_GATE_SECONDS,
  "faultInjection": "$FAULT_INJECTION"
}
METADATA

docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
  images > "$RESULT_DIR/image-digests.txt"

# 3. 清理 Redis 缓存与重置 MySQL 状态
echo "[$(date +'%T')] 清理 Redis 缓存并重置 MySQL 状态计数器..."
docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
  exec -T redis redis-cli -n 1 flushdb >/dev/null

MYSQL_EXEC=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT"
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml"
  exec -T mysql sh -c 'mysql -uexam_runtime -p"$EXAM_DB_PASSWORD" exam_runtime'
)
MYSQL_EXEC_RAW=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT"
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml"
  exec -T mysql sh -c 'mysql -uexam_runtime -p"$EXAM_DB_PASSWORD" -N exam_runtime'
)

docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
  exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "FLUSH STATUS; TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;"' \
  >/dev/null 2>&1 || true

# 4. 准备数据
echo "[$(date +'%T')] 准备 $USERS 人压测数据 (到期时间: ${DUE_SECONDS}s)..."
PREPARE_OUTPUT=$(USERS="$USERS" EXAM_ID="$EXAM_ID" DUE_IN_SECONDS="$DUE_SECONDS" node "$SCRIPT_DIR/prepare-timeout-load-data.mjs")
echo "$PREPARE_OUTPUT" | tail -n 2

DEADLINE_EPOCH_MS=$(echo "$PREPARE_OUTPUT" | grep -o '"deadlineEpochMs":[0-9]*' | cut -d':' -f2)
chmod 644 "$TOKEN_FILE"

RESOURCE_TIMESERIES_FILE="$RESULT_DIR/container-resource-timeseries.tsv"
(
  while true; do
    timestamp=$(date --iso-8601=ns)
    docker stats --no-stream \
      --format "$timestamp\t{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}\t{{.PIDs}}" \
      $(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
        -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" ps -q) \
      2>/dev/null || true
    sleep 1
  done
) > "$RESOURCE_TIMESERIES_FILE" &
RESOURCE_SAMPLER_PID=$!

FAULT_TIMELINE_FILE="$RESULT_DIR/fault-timeline.txt"
TARGET_CONTAINER_ID=""
TARGET_CONTAINER_NAME=""
if [ "$FAULT_INJECTION" = "node_crash" ]; then
  mapfile -t runtime_container_ids < <(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
    -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
    ps -q runtime-service)
  if [ "${#runtime_container_ids[@]}" -lt 2 ]; then
    echo "node_crash 场景至少需要两个 Runtime 副本" >&2
    exit 2
  fi
  TARGET_CONTAINER_ID="${runtime_container_ids[-1]}"
  TARGET_CONTAINER_NAME=$(docker inspect --format '{{.Name}}' "$TARGET_CONTAINER_ID" | sed 's#^/##')
  for container_id in "${runtime_container_ids[@]:0:${#runtime_container_ids[@]}-1}"; do
    docker pause "$container_id" >/dev/null
    FAULT_PAUSED_CONTAINER_IDS+=("$container_id")
  done
  {
    echo "fault=node_crash"
    echo "target_container=$TARGET_CONTAINER_NAME"
    echo "paused_survivor_count=${#FAULT_PAUSED_CONTAINER_IDS[@]}"
    echo "isolation_started_at=$(date --iso-8601=seconds)"
  } > "$FAULT_TIMELINE_FILE"
  echo "[$(date +'%T')] [故障准备] 已暂停 ${#FAULT_PAUSED_CONTAINER_IDS[@]} 个副本，确保在途任务只属于 $TARGET_CONTAINER_NAME"
fi

K6_SUMMARY_FILE="$RESULT_DIR/k6-summary.json"
touch "$K6_SUMMARY_FILE"
chmod 666 "$K6_SUMMARY_FILE"

K6_EXIT_CODE=0
if [ "$ENABLE_POLLING" = "true" ]; then
  REMAINING_SECS=$(node -e "console.log(Math.max(0, Math.ceil(($DEADLINE_EPOCH_MS - Date.now())/1000)))")
  echo "[$(date +'%T')] 距离考试截止还有 ${REMAINING_SECS} 秒，启动 k6 轮询..."

  K6_CMD=(docker run --rm --network host --ulimit nofile=1048576:1048576
    -v "$SCRIPT_DIR:/load-test"
    -v "$RESULT_DIR:/results"
    -e BASE_URL="http://localhost:16730/api/v1"
    -e EXAM_ID="$EXAM_ID"
    -e DEADLINE_EPOCH_MS="$DEADLINE_EPOCH_MS"
    -e TOKENS_FILE="/load-test/tokens-$EXAM_ID.json"
    -e USERS="$USERS"
    -e POLL_BASE_MS="$POLL_BASE_MS"
    -e POLL_MAX_MS="$POLL_MAX_MS"
    -e LOAD_FLOW="$LOAD_FLOW"
    -e DB_P99_GATE_MS="$(awk -v seconds="$DB_P99_GATE_SECONDS" 'BEGIN { printf "%.0f", seconds * 1000 }')"
    -e DB_MAX_GATE_MS="$(awk -v seconds="$DB_MAX_GATE_SECONDS" 'BEGIN { printf "%.0f", seconds * 1000 }')"
    grafana/k6 run --summary-export="/results/k6-summary.json" /load-test/timeout-submission.js
  )

  "${K6_CMD[@]}" || K6_EXIT_CODE=$?
fi

# 5. 基于 due_at 的有界等待
echo ""
echo "[$(date +'%T')] 进入基于截止时间的收敛等待阶段..."
DEADLINE_SEC=$((DEADLINE_EPOCH_MS / 1000))

# 等待到达截止时间
CURRENT_SEC=$(date +%s)
if [ "$CURRENT_SEC" -lt "$DEADLINE_SEC" ]; then
  SLEEP_WAIT=$((DEADLINE_SEC - CURRENT_SEC))
  echo "[$(date +'%T')] 等待截止时间到来 (${SLEEP_WAIT}s)..."
  sleep "$SLEEP_WAIT"
fi

# 截止后 30 秒采集一次中间收敛状态
echo "[$(date +'%T')] 到期开始收敛，等待 30 秒中间检查点..."
FAULT_INJECTION_FAILED=0
FAULT_INFLIGHT_IDS=""
FAULT_INFLIGHT_COUNT=0
FAULT_RECOVERED_INFLIGHT=0

wait_until_epoch() {
  local target_epoch="$1"
  local current_epoch
  current_epoch=$(date +%s)
  if [ "$current_epoch" -lt "$target_epoch" ]; then
    sleep "$((target_epoch - current_epoch))"
  fi
}

if [ "$FAULT_INJECTION" = "node_crash" ]; then
  echo "[$(date +'%T')] [故障注入] 等待数据库出现 PROCESSING 任务..."
  fault_wait_started=$(date +%s)
  while true; do
    candidate_ids=$(echo "SELECT GROUP_CONCAT(id ORDER BY id) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='PROCESSING';" | "${MYSQL_EXEC_RAW[@]}")
    if [ -n "$candidate_ids" ] && [ "$candidate_ids" != "NULL" ]; then
      docker pause "$TARGET_CONTAINER_ID" >/dev/null
      FAULT_TARGET_PAUSED=true
      sleep 0.2
      stable_ids=$(echo "SELECT GROUP_CONCAT(id ORDER BY id) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='PROCESSING';" | "${MYSQL_EXEC_RAW[@]}")
      if [ -n "$stable_ids" ] && [ "$stable_ids" != "NULL" ]; then
        FAULT_INFLIGHT_IDS="$stable_ids"
        FAULT_INFLIGHT_COUNT=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='PROCESSING';" | "${MYSQL_EXEC_RAW[@]}")
        break
      fi
      docker unpause "$TARGET_CONTAINER_ID" >/dev/null
      FAULT_TARGET_PAUSED=false
    fi
    if [ "$(( $(date +%s) - fault_wait_started ))" -ge "$FAULT_WAIT_TIMEOUT_SECONDS" ]; then
      echo "[$(date +'%T')] [故障注入] 未在 ${FAULT_WAIT_TIMEOUT_SECONDS}s 内观察到 PROCESSING 任务"
      FAULT_INJECTION_FAILED=1
      break
    fi
    sleep 0.2
  done

  if [ "$FAULT_INJECTION_FAILED" -eq 0 ]; then
    if [ -z "$TARGET_CONTAINER_ID" ]; then
      echo "[$(date +'%T')] [故障注入] 未找到 Runtime 容器"
      FAULT_INJECTION_FAILED=1
    else
      DB_INJECTION_TIME=$(echo "SELECT CURRENT_TIMESTAMP(3);" | "${MYSQL_EXEC_RAW[@]}")
      {
        echo "database_injection_time=$DB_INJECTION_TIME"
        echo "processing_count_before_kill=$FAULT_INFLIGHT_COUNT"
        echo "processing_task_ids_before_kill=$FAULT_INFLIGHT_IDS"
      } >> "$FAULT_TIMELINE_FILE"
      echo "[$(date +'%T')] [故障注入] PROCESSING=$FAULT_INFLIGHT_COUNT，强制杀死 $TARGET_CONTAINER_NAME"
      if ! docker kill "$TARGET_CONTAINER_ID" >/dev/null; then
        echo "[$(date +'%T')] [故障注入] docker kill 执行失败"
        FAULT_INJECTION_FAILED=1
      else
        FAULT_TARGET_PAUSED=false
      fi
    fi
  fi

  for container_id in "${FAULT_PAUSED_CONTAINER_IDS[@]}"; do
    docker unpause "$container_id" >/dev/null 2>&1 || true
  done
  echo "survivors_resumed_at=$(date --iso-8601=seconds)" >> "$FAULT_TIMELINE_FILE"
fi

wait_until_epoch "$((DEADLINE_SEC + 30))"
DONE_COUNT_30=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE';" | "${MYSQL_EXEC_RAW[@]}")
echo "[$(date +'%T')] [Checkpoint 30s] 当前已完成交卷: $DONE_COUNT_30 / $USERS"

# 截止后 60 秒停止等待并断言
echo "[$(date +'%T')] 等待截止后 60 秒最终收敛截止线..."
wait_until_epoch "$((DEADLINE_SEC + 60))"
DONE_COUNT_60=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE';" | "${MYSQL_EXEC_RAW[@]}")
echo "[$(date +'%T')] [Checkpoint 60s] 60秒截止线已完成交卷: $DONE_COUNT_60 / $USERS"

if [ "$FAULT_INJECTION" = "node_crash" ] && [ -n "$FAULT_INFLIGHT_IDS" ]; then
  FAULT_RECOVERED_INFLIGHT=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND id IN ($FAULT_INFLIGHT_IDS) AND status='DONE' AND attempt_count>1;" | "${MYSQL_EXEC_RAW[@]}")
  {
    echo "recovered_inflight_tasks=$FAULT_RECOVERED_INFLIGHT"
    echo "done_at_30s=$DONE_COUNT_30"
    echo "done_at_60s=$DONE_COUNT_60"
  } >> "$FAULT_TIMELINE_FILE"
fi

# 6. 查询并保存 MySQL 权威统计与窗口函数延迟
echo ""
echo "[$(date +'%T')] 查询 MySQL 权威统计指标并保存报告..."

WINDOW_SQL="
WITH latencies AS (
    SELECT TIMESTAMPDIFF(MICROSECOND, due_at, completed_at) / 1000.0 AS latency_ms
    FROM submission_timeout_task
    WHERE exam_id = $EXAM_ID AND status = 'DONE'
), ranked AS (
    SELECT latency_ms,
           ROW_NUMBER() OVER (ORDER BY latency_ms) AS row_num,
           COUNT(*) OVER () AS done_count
    FROM latencies
)
SELECT
    COUNT(*) AS done_tasks,
    MIN(latency_ms) / 1000.0 AS min_s,
    AVG(latency_ms) / 1000.0 AS avg_s,
    MAX(CASE WHEN row_num = CEIL(done_count * 0.50) THEN latency_ms END) / 1000.0 AS p50_s,
    MAX(CASE WHEN row_num = CEIL(done_count * 0.90) THEN latency_ms END) / 1000.0 AS p90_s,
    MAX(CASE WHEN row_num = CEIL(done_count * 0.95) THEN latency_ms END) / 1000.0 AS p95_s,
    MAX(CASE WHEN row_num = CEIL(done_count * 0.99) THEN latency_ms END) / 1000.0 AS p99_s,
    MAX(latency_ms) / 1000.0 AS max_s
FROM ranked;
"

LATENCY_RESULT=$(echo "$WINDOW_SQL" | "${MYSQL_EXEC[@]}")
echo "$LATENCY_RESULT"
echo "$LATENCY_RESULT" > "$RESULT_DIR/database-summary.txt"

# 7. 十项数据库一致性断言校验
ASSERT_SQL="
SELECT
  (SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE') AS done_tasks,
  (SELECT COUNT(*) FROM exam_session WHERE exam_id=$EXAM_ID AND status='SUBMITTED') AS submitted_sessions,
  (SELECT COUNT(*) FROM submission WHERE exam_id=$EXAM_ID AND status='PROCESSING') AS processing_submissions,
  (SELECT COUNT(*) FROM submission_final_payload WHERE submission_id IN (SELECT id FROM submission WHERE exam_id=$EXAM_ID)) AS final_payloads,
  (SELECT COUNT(*) FROM submission_draft_payload WHERE submission_id IN (SELECT id FROM submission WHERE exam_id=$EXAM_ID)) AS draft_payloads,
  (SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND submission_id IS NULL) AS null_submission_ids,
  (SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='FAILED') AS failed_tasks,
  (SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='PROCESSING') AS lingering_processing_tasks,
  (SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='SUBMISSION' AND event_type='SubmissionAccepted' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM submission WHERE exam_id=$EXAM_ID)) AS outbox_accepted_events,
  (SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='SUBMISSION' AND status='FAILED') AS outbox_failed_events;
"

ASSERT_OUTPUT=$(echo "$ASSERT_SQL" | "${MYSQL_EXEC[@]}")
echo ""
echo "--- 数据库一致性检查结果 ---"
echo "$ASSERT_OUTPUT"
echo "$ASSERT_OUTPUT" >> "$RESULT_DIR/database-summary.txt"

# 重试分布
echo "--- 尝试次数分布 ---" >> "$RESULT_DIR/database-summary.txt"
echo "SELECT attempt_count, count(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID GROUP BY attempt_count;" | "${MYSQL_EXEC[@]}" >> "$RESULT_DIR/database-summary.txt"

# 8. 采集资源与时序指标
echo ""
echo "[$(date +'%T')] 采集 MySQL 资源与性能指标..."
RESOURCE_SQL="
SHOW STATUS WHERE Variable_name IN (
  'Max_used_connections',
  'Threads_connected',
  'Threads_running',
  'Innodb_row_lock_waits',
  'Innodb_row_lock_time_avg',
  'Innodb_row_lock_time_max'
);
"
echo "$RESOURCE_SQL" | "${MYSQL_EXEC[@]}" > "$RESULT_DIR/resource-summary.txt"
docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
  exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "
    SELECT COUNT_STAR,
           ROUND(SUM_TIMER_WAIT/1000000000000,3) total_s,
           ROUND(AVG_TIMER_WAIT/1000000000,3) avg_ms,
           LEFT(DIGEST_TEXT,240)
      FROM performance_schema.events_statements_summary_by_digest
     WHERE SCHEMA_NAME=\"exam_runtime\"
     ORDER BY SUM_TIMER_WAIT DESC
     LIMIT 30;
  "' > "$RESULT_DIR/mysql-top-statements.tsv" 2>/dev/null || true
echo "--- Redis DB 1 Key Count ---" >> "$RESULT_DIR/resource-summary.txt"
docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
  exec -T redis redis-cli -n 1 dbsize >> "$RESULT_DIR/resource-summary.txt"

docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml" \
  logs --no-color --since "$RUN_STARTED_AT" runtime-service 2>&1 \
  | grep -E 'Timeout submission initialized|Timeout submission retry scheduled|超时交卷批次超过任务总预算|projection|post-commit|reconciliation' \
  > "$RESULT_DIR/runtime-events.log" || true

if [ -n "$RESOURCE_SAMPLER_PID" ]; then
  kill "$RESOURCE_SAMPLER_PID" >/dev/null 2>&1 || true
  wait "$RESOURCE_SAMPLER_PID" 2>/dev/null || true
  RESOURCE_SAMPLER_PID=""
fi

# 9. 门禁断言判定 (Gate Verification)
GATE_FAILED=0

# 读取延迟数值
P99_VAL=$(echo "$LATENCY_RESULT" | awk 'NR==2 {print $7}')
MAX_VAL=$(echo "$LATENCY_RESULT" | awk 'NR==2 {print $8}')

# 读取重试次数
RETRY_COUNT=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND attempt_count > 1;" | "${MYSQL_EXEC_RAW[@]}")

echo "================================================================"
echo "                   自动化上线闸门检查 (GATE CHECK)               "
echo "================================================================"

# 断言 1: k6 客户端退出码（纯后台场景明确标记为跳过）
if [ "$ENABLE_POLLING" != "true" ]; then
  echo -e "\033[33m[SKIP]\033[0m 当前为纯后台场景，未执行 k6 客户端阈值校验"
elif [ "$K6_EXIT_CODE" -ne 0 ]; then
  echo -e "\033[31m[FAIL]\033[0m k6 thresholds 未完全通过 (exit code: $K6_EXIT_CODE)"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m k6 客户端阈值校验通过"
fi

# 断言 2: 任务全量收敛 (60s 内)
if [ "$DONE_COUNT_60" -ne "$USERS" ]; then
  echo -e "\033[31m[FAIL]\033[0m 60s 内收敛任务未达 100%: $DONE_COUNT_60 / $USERS"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m 10,000 任务在 60s 内全量收敛 (100%)"
fi

# 断言 3: P99 门槛
if (( $(echo "$P99_VAL >= $DB_P99_GATE_SECONDS" | bc -l 2>/dev/null || echo 1) )); then
  echo -e "\033[31m[FAIL]\033[0m 数据库权威 P99 耗时未达标: ${P99_VAL}s (要求 < ${DB_P99_GATE_SECONDS}s)"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m 数据库权威 P99 耗时达标: ${P99_VAL}s (< ${DB_P99_GATE_SECONDS}s)"
fi

# 断言 4: 最大耗时门槛
if (( $(echo "$MAX_VAL >= $DB_MAX_GATE_SECONDS" | bc -l 2>/dev/null || echo 1) )); then
  echo -e "\033[31m[FAIL]\033[0m 数据库权威最大耗时未达标: ${MAX_VAL}s (要求 < ${DB_MAX_GATE_SECONDS}s)"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m 数据库权威最大耗时达标: ${MAX_VAL}s (< ${DB_MAX_GATE_SECONDS}s)"
fi

# 断言 5: 重试率 < 0.5% (50条)
MAX_ALLOWED_RETRY=$((USERS * 5 / 1000))
if [ "$RETRY_COUNT" -gt "$MAX_ALLOWED_RETRY" ]; then
  echo -e "\033[31m[FAIL]\033[0m 任务重试次数超标: $RETRY_COUNT (要求 <= $MAX_ALLOWED_RETRY)"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m 任务重试率受控: $RETRY_COUNT / $USERS (< 0.5%)"
fi

# 断言 6: FAILED 任务数为 0
FAILED_COUNT=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='FAILED';" | "${MYSQL_EXEC_RAW[@]}")
if [ "$FAILED_COUNT" -ne 0 ]; then
  echo -e "\033[31m[FAIL]\033[0m 存在 FAILED 状态异常任务: $FAILED_COUNT"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m 零 FAILED 状态任务"
fi

# 断言 7: 无遗留 PROCESSING 任务
PROCESSING_COUNT=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='PROCESSING';" | "${MYSQL_EXEC_RAW[@]}")
if [ "$PROCESSING_COUNT" -ne 0 ]; then
  echo -e "\033[31m[FAIL]\033[0m 存在长期 PROCESSING 状态任务: $PROCESSING_COUNT"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m 零长期 PROCESSING 状态任务"
fi

# 断言 8: 无 NULL submission_id
NULL_SUB_COUNT=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND submission_id IS NULL;" | "${MYSQL_EXEC_RAW[@]}")
if [ "$NULL_SUB_COUNT" -ne 0 ]; then
  echo -e "\033[31m[FAIL]\033[0m 存在未关联 submission_id 的任务: $NULL_SUB_COUNT"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m 全部任务正常回填 submission_id"
fi

# 断言 9: 最终载荷与草稿数量精准匹配
FINAL_PAYLOAD_COUNT=$(echo "SELECT COUNT(*) FROM submission_final_payload WHERE submission_id IN (SELECT id FROM submission WHERE exam_id=$EXAM_ID);" | "${MYSQL_EXEC_RAW[@]}")
DRAFT_PAYLOAD_COUNT=$(echo "SELECT COUNT(*) FROM submission_draft_payload WHERE submission_id IN (SELECT id FROM submission WHERE exam_id=$EXAM_ID);" | "${MYSQL_EXEC_RAW[@]}")
if [ "$FINAL_PAYLOAD_COUNT" -ne "$USERS" ] || [ "$DRAFT_PAYLOAD_COUNT" -ne "$USERS" ]; then
  echo -e "\033[31m[FAIL]\033[0m 载荷数量不匹配: final=$FINAL_PAYLOAD_COUNT, draft=$DRAFT_PAYLOAD_COUNT, expected=$USERS"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m 最终载荷与数据库主草稿数量精准匹配 ($FINAL_PAYLOAD_COUNT)"
fi

# 断言 10: Session、Submission 与逻辑 Outbox 数量精准匹配
SUBMITTED_SESSION_COUNT=$(echo "SELECT COUNT(*) FROM exam_session WHERE exam_id=$EXAM_ID AND status='SUBMITTED';" | "${MYSQL_EXEC_RAW[@]}")
PROCESSING_SUBMISSION_COUNT=$(echo "SELECT COUNT(*) FROM submission WHERE exam_id=$EXAM_ID AND status='PROCESSING';" | "${MYSQL_EXEC_RAW[@]}")
OUTBOX_ACCEPTED_COUNT=$(echo "SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='SUBMISSION' AND event_type='SubmissionAccepted' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM submission WHERE exam_id=$EXAM_ID);" | "${MYSQL_EXEC_RAW[@]}")
if [ "$SUBMITTED_SESSION_COUNT" -ne "$USERS" ] \
  || [ "$PROCESSING_SUBMISSION_COUNT" -ne "$USERS" ] \
  || [ "$OUTBOX_ACCEPTED_COUNT" -ne "$USERS" ]; then
  echo -e "\033[31m[FAIL]\033[0m 业务状态数量不匹配: sessions=$SUBMITTED_SESSION_COUNT, submissions=$PROCESSING_SUBMISSION_COUNT, outbox=$OUTBOX_ACCEPTED_COUNT, expected=$USERS"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m Session、Submission 与逻辑 Outbox 数量精准匹配"
fi

# 断言 11: 当前考试的 Outbox 事件零失败
OUTBOX_FAILED=$(echo "SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='SUBMISSION' AND status='FAILED' AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM submission WHERE exam_id=$EXAM_ID);" | "${MYSQL_EXEC_RAW[@]}")
if [ "$OUTBOX_FAILED" -ne 0 ]; then
  echo -e "\033[31m[FAIL]\033[0m 当前考试 Outbox 存在发布失败事件: $OUTBOX_FAILED"
  GATE_FAILED=1
else
  echo -e "\033[32m[PASS]\033[0m 当前考试 Outbox 事件零失败"
fi

# 断言 12: 节点崩溃场景必须确实发生，并至少恢复一条注入瞬间的在途任务
if [ "$FAULT_INJECTION" = "node_crash" ]; then
  if [ "$FAULT_INJECTION_FAILED" -ne 0 ]; then
    echo -e "\033[31m[FAIL]\033[0m 节点崩溃故障未被有效注入"
    GATE_FAILED=1
  elif [ "$FAULT_RECOVERED_INFLIGHT" -le 0 ]; then
    echo -e "\033[31m[FAIL]\033[0m 未证明注入瞬间的在途任务发生租约恢复"
    GATE_FAILED=1
  else
    echo -e "\033[32m[PASS]\033[0m 注入瞬间在途任务中有 $FAULT_RECOVERED_INFLIGHT 条经重试恢复"
  fi
fi

echo "================================================================"

if [ "$GATE_FAILED" -ne 0 ]; then
  echo -e "\033[31m[GATE CHECK FAILED] 压测场景 $SCENARIO 未完全通过全部放行闸门，详见报告目录: $RESULT_DIR\033[0m"
  exit 1
else
  echo -e "\033[32m[GATE CHECK PASSED] 压测场景 $SCENARIO 全部放行闸门检验通过！报告目录: $RESULT_DIR\033[0m"
  exit 0
fi
