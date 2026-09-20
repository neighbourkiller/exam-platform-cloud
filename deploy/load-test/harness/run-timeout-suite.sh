#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOAD_TEST_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$LOAD_TEST_ROOT/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"
COMPOSE_PROJECT="exam-platform-cloud-timeout"
source "$SCRIPT_DIR/process-lifecycle.sh"
umask 077

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
OBSERVATION_MODE="${OBSERVATION_MODE:-off}"
TIMEOUT_DIAGNOSTIC="${TIMEOUT_DIAGNOSTIC:-false}"
DIAGNOSTIC_SAMPLE_STRIDE="${DIAGNOSTIC_SAMPLE_STRIDE:-10}"
OBSERVATION_KILL_ON="${OBSERVATION_KILL_ON:-CLAIM_HELD}"
OBSERVATION_WAIT_TIMEOUT_SECONDS="${OBSERVATION_WAIT_TIMEOUT_SECONDS:-30}"
OBSERVATION_LIMIT_SECONDS="${OBSERVATION_LIMIT_SECONDS:-180}"
K6_IMAGE="grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec"
FAULT_PAUSED_CONTAINER_IDS=()
FAULT_TARGET_PAUSED=false
RESOURCE_SAMPLER_PID=""
REGISTRY_SAMPLER_PID=""
PROM_SAMPLER_PID=""
K6_PID=""
RUN_STARTED_AT="$(date --iso-8601=seconds)"
K6_EXIT_CODE="NOT_OBTAINED"
K6_WAITED=false
K6_CONTAINER_NAME=""
K6_CONTAINER_CREATED=false
K6_CONTAINER_STATE="unknown"
CLEANUP_ERROR=0
SAMPLER_STOP_ERROR=0
CLEANUP_DONE=false
RUNTIME_IMAGE_CONTEXT_CREATED=false
TARGET_TASK_ID="null"
FAULT_TARGET_INSTANCE_ID=""

CROSS_SHARD_DELAY_MS="${APP_TIMEOUT_SUBMISSION_CROSS_SHARD_DELAY_MS:-10000}"
if ! [[ "$DB_P99_GATE_SECONDS" =~ ^[0-9]+([.][0-9]+)?$ ]] \
  || ! [[ "$DB_MAX_GATE_SECONDS" =~ ^[0-9]+([.][0-9]+)?$ ]] \
  || (( $(echo "$DB_P99_GATE_SECONDS <= 0 || $DB_MAX_GATE_SECONDS <= 0 || $DB_MAX_GATE_SECONDS <= $DB_P99_GATE_SECONDS" | bc -l 2>/dev/null || echo 1) )); then
  echo "数据库门槛必须为正数，且 DB_MAX_GATE_SECONDS 必须大于 DB_P99_GATE_SECONDS" >&2
  exit 2
fi

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
RESULT_DIR="$LOAD_TEST_ROOT/results/${TIMESTAMP}-${SCENARIO}"
mkdir -p "$RESULT_DIR"
TOKEN_FILE="$(mktemp "$RESULT_DIR/.timeout-tokens.XXXXXX")"
RUNTIME_IMAGE_CONTEXT_DIR="$LOAD_TEST_ROOT/.runtime-image"
export TIMEOUT_TEST_RESULT_DIR="$RESULT_DIR"

case "$OBSERVATION_MODE" in
  off|observe|hold) ;;
  *) echo "OBSERVATION_MODE 必须为 off、observe 或 hold" >&2; exit 2 ;;
esac
if [ "$OBSERVATION_MODE" != "off" ] || [ "$TIMEOUT_DIAGNOSTIC" = "true" ]; then
  OBSERVATION_ENABLED=true
  OBSERVATION_COMPOSE_FILE_ARGS=(-f "$LOAD_TEST_ROOT/compose/compose.timeout-observation.yaml")
else
  OBSERVATION_ENABLED=false
  OBSERVATION_COMPOSE_FILE_ARGS=()
fi
if [ "$OBSERVATION_MODE" = "hold" ] \
  && [ "$OBSERVATION_KILL_ON" != "CLAIM_HELD" ] \
  && [ "$OBSERVATION_KILL_ON" != "RENEW_SUCCEEDED" ]; then
  echo "OBSERVATION_KILL_ON 必须为 CLAIM_HELD 或 RENEW_SUCCEEDED" >&2
  exit 2
fi
if [ "$TIMEOUT_DIAGNOSTIC" = "true" ] \
  && ! [[ "$DIAGNOSTIC_SAMPLE_STRIDE" =~ ^[1-9][0-9]*$ ]]; then
  echo "DIAGNOSTIC_SAMPLE_STRIDE 必须为正整数" >&2
  exit 2
fi

stop_managed_process() {
  local pid_variable="$1"
  local pid="${!pid_variable:-}"
  local stop_code=0

  [ -n "$pid" ] || return 0
  if stop_process_group "$pid" 8; then
    stop_code=0
  else
    stop_code=$?
  fi
  # stop_process_group 已经 wait 完成，之后才清空 PID。
  printf -v "$pid_variable" '%s' ''
  if [ "$stop_code" -ne 0 ] && [ "$stop_code" -ne 130 ] && [ "$stop_code" -ne 143 ]; then
    SAMPLER_STOP_ERROR=1
    echo "[工具错误] 进程组 PID=$pid 停止退出码=$stop_code" >&2
  fi
}

cleanup_k6_container() {
  [ -n "$K6_CONTAINER_NAME" ] || return 0
  if ! docker inspect "$K6_CONTAINER_NAME" >/dev/null 2>&1; then
    return 0
  fi
  local state
  state="$(docker inspect --format '{{.State.Status}}' "$K6_CONTAINER_NAME" 2>/dev/null || echo unknown)"
  if [ "$state" = "running" ] || [ "$state" = "created" ]; then
    if ! docker rm -f "$K6_CONTAINER_NAME" >/dev/null 2>&1; then
      CLEANUP_ERROR=1
      echo "[工具错误] 无法回收本轮 k6 容器: $K6_CONTAINER_NAME" >&2
    fi
  elif ! docker rm "$K6_CONTAINER_NAME" >/dev/null 2>&1; then
    CLEANUP_ERROR=1
    echo "[工具错误] 无法删除已结束的本轮 k6 容器: $K6_CONTAINER_NAME" >&2
  fi
}

cleanup() {
  [ "$CLEANUP_DONE" = true ] && return 0
  CLEANUP_DONE=true

  # 异常退出时先终止 k6，再回收采样器；每个 PID 单独 wait，不能互相清空。
  stop_managed_process K6_PID
  cleanup_k6_container
  stop_managed_process REGISTRY_SAMPLER_PID
  stop_managed_process PROM_SAMPLER_PID
  stop_managed_process RESOURCE_SAMPLER_PID

  if [ "$FAULT_TARGET_PAUSED" = "true" ] && [ -n "${TARGET_CONTAINER_ID:-}" ]; then
    docker unpause "$TARGET_CONTAINER_ID" >/dev/null 2>&1 || true
  fi
  for container_id in "${FAULT_PAUSED_CONTAINER_IDS[@]}"; do
    docker unpause "$container_id" >/dev/null 2>&1 || true
  done

  if [ "$RUNTIME_IMAGE_CONTEXT_CREATED" = true ]; then
    rm -f "$RUNTIME_IMAGE_CONTEXT_DIR/app.jar" || CLEANUP_ERROR=1
    rm -f "$RUNTIME_IMAGE_CONTEXT_DIR/timeout-observer-addon.jar" || CLEANUP_ERROR=1
    rmdir "$RUNTIME_IMAGE_CONTEXT_DIR" >/dev/null 2>&1 || true
    RUNTIME_IMAGE_CONTEXT_CREATED=false
  fi
  rm -f "$TOKEN_FILE" || CLEANUP_ERROR=1

  if [ "$FAULT_INJECTION" = "node_crash" ]; then
    if ! docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
      -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
      "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
      up -d --scale runtime-service="$REPLICAS" runtime-service >/dev/null 2>&1; then
      CLEANUP_ERROR=1
      echo "[工具错误] 无法恢复 node_crash 场景的 Runtime 副本数" >&2
    fi
  fi
}

on_exit() {
  local exit_code=$?
  trap - EXIT
  cleanup
  if [ "$exit_code" -eq 0 ] && [ "$CLEANUP_ERROR" -ne 0 ]; then
    exit_code=2
  fi
  exit "$exit_code"
}

trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
exec > >(tee "$RESULT_DIR/run.log") 2>&1

echo "================================================================"
echo "  执行超时交卷压测场景: $SCENARIO"
echo "  结果保存目录: $RESULT_DIR"
echo "  Runtime 副本数: $REPLICAS, 工作者数: $WORKERS"
echo "  MySQL max_connections: $MYSQL_MAX_CONN, 用户规模: $USERS"
echo "  Runtime 单实例数据库连接池: $RUNTIME_DB_POOL_SIZE"
echo "  到期时间偏移: ${DUE_SECONDS}s, 客户端轮询: $ENABLE_POLLING"
echo "  数据库门槛: P99 < ${DB_P99_GATE_SECONDS}s, max < ${DB_MAX_GATE_SECONDS}s"
echo "  观察组件: mode=$OBSERVATION_MODE, diagnostic=$TIMEOUT_DIAGNOSTIC, sampleStride=$DIAGNOSTIC_SAMPLE_STRIDE"
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
  MAVEN_OBSERVER_ARGS=()
  if [ "$OBSERVATION_ENABLED" = true ]; then
    MAVEN_OBSERVER_ARGS=(-Ptimeout-observer-addon)
  fi
  JAVA_HOME="${JAVA_HOME:-}" bash "$PROJECT_ROOT/mvnw" \
    -pl services/exam-runtime-service -am "${MAVEN_OBSERVER_ARGS[@]}" package -DskipTests \
    | tee "$RESULT_DIR/runtime-package.log"

  if [ -e "$RUNTIME_IMAGE_CONTEXT_DIR/app.jar" ] \
    || [ -e "$RUNTIME_IMAGE_CONTEXT_DIR/timeout-observer-addon.jar" ]; then
    echo "压测镜像上下文已存在运行时文件，拒绝覆盖用户文件: $RUNTIME_IMAGE_CONTEXT_DIR" >&2
    exit 2
  fi
  if [ ! -e "$RUNTIME_IMAGE_CONTEXT_DIR" ]; then
    mkdir -p "$RUNTIME_IMAGE_CONTEXT_DIR"
    RUNTIME_IMAGE_CONTEXT_CREATED=true
  fi
  cp "$PROJECT_ROOT/services/exam-runtime-service/target/exam-runtime-service-1.0.0-SNAPSHOT.jar" \
    "$RUNTIME_IMAGE_CONTEXT_DIR/app.jar"
  if [ "$OBSERVATION_ENABLED" = true ]; then
    OBSERVER_ADDON_JAR="$PROJECT_ROOT/services/exam-runtime-service/target/exam-runtime-service-1.0.0-SNAPSHOT-timeout-observer.jar"
    if [ ! -f "$OBSERVER_ADDON_JAR" ]; then
      echo "未生成 timeout-observer 附加 JAR: $OBSERVER_ADDON_JAR" >&2
      exit 2
    fi
    cp "$OBSERVER_ADDON_JAR" "$RUNTIME_IMAGE_CONTEXT_DIR/timeout-observer-addon.jar"
  fi

  echo "[$(date +'%T')] 构建当前工作区 Runtime 镜像..."
  docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
    -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
    "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
    build --progress plain runtime-service | tee "$RESULT_DIR/runtime-image-build.log"
  rm -f "$RUNTIME_IMAGE_CONTEXT_DIR/app.jar"
  rm -f "$RUNTIME_IMAGE_CONTEXT_DIR/timeout-observer-addon.jar"
  if [ "$RUNTIME_IMAGE_CONTEXT_CREATED" = true ]; then
    rmdir "$RUNTIME_IMAGE_CONTEXT_DIR" >/dev/null 2>&1 || true
    RUNTIME_IMAGE_CONTEXT_CREATED=false
  fi
else
  echo "[$(date +'%T')] 复用显式指定的现有 Runtime 镜像（BUILD_RUNTIME_IMAGE=false）"
fi

echo "[$(date +'%T')] 编排与启动容器集群 (runtime-service=$REPLICAS)..."
if [ "$OBSERVATION_ENABLED" = true ]; then
  # 观察初始化器要求控制清单在 Runtime 启动前已经存在；先启动依赖和
  # IAM 以便准备数据脚本读取 Compose 管理的测试密钥。
  docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
    -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
    "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
    up -d mysql redis rabbitmq minio nacos nacos-config-init jwt-key-init xxl-job-admin iam-service
else
  docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
    -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
    up -d --scale runtime-service="$REPLICAS" \
    mysql redis rabbitmq minio nacos nacos-config-init jwt-key-init xxl-job-admin iam-service gateway runtime-service
fi

# 2. 收集镜像摘要与 Git 信息
GIT_COMMIT="$(git -C "$PROJECT_ROOT" rev-parse HEAD 2>/dev/null || echo 'UNKNOWN')"
GIT_DIRTY=false
if [ -n "$(git -C "$PROJECT_ROOT" status --porcelain)" ]; then
  GIT_DIRTY=true
fi
CODE_DIFF_ID="$( (git -C "$PROJECT_ROOT" rev-parse HEAD; git -C "$PROJECT_ROOT" diff; git -C "$PROJECT_ROOT" status --porcelain) 2>/dev/null | sha256sum | cut -c1-16 )"
RUNTIME_IMAGE_ID=$(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
  "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
  images -q runtime-service | head -n 1)
RUNTIME_IMAGE_DIGEST="$(docker inspect --format '{{index .RepoDigests 0}}' "$RUNTIME_IMAGE_ID" 2>/dev/null || true)"
RUNTIME_IMAGE_DIGEST="$(printf '%s' "$RUNTIME_IMAGE_DIGEST" | tr -d '\r\n')"
[ -n "$RUNTIME_IMAGE_DIGEST" ] || RUNTIME_IMAGE_DIGEST="no-digest"
cat << METADATA > "$RESULT_DIR/metadata.json"
{
  "scenario": "$SCENARIO",
  "timestamp": "$TIMESTAMP",
  "gitCommit": "$GIT_COMMIT",
  "gitDirty": $GIT_DIRTY,
  "codeDiffId": "$CODE_DIFF_ID",
  "runtimeImageId": "$RUNTIME_IMAGE_ID",
  "runtimeImageDigest": "$RUNTIME_IMAGE_DIGEST",
  "runtimeImageBuiltByThisRun": $BUILD_RUNTIME_IMAGE,
  "k6Image": "$K6_IMAGE",
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
  "observationMode": "$OBSERVATION_MODE",
  "timeoutDiagnostic": $TIMEOUT_DIAGNOSTIC,
  "diagnosticSampleStride": $DIAGNOSTIC_SAMPLE_STRIDE,
  "targetTaskId": $TARGET_TASK_ID,
  "crossShardDelayMs": $CROSS_SHARD_DELAY_MS,
  "dbP99GateSeconds": $DB_P99_GATE_SECONDS,
  "dbMaxGateSeconds": $DB_MAX_GATE_SECONDS,
  "faultInjection": "$FAULT_INJECTION"
}
METADATA

docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
  "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
  images > "$RESULT_DIR/image-digests.txt"
echo "k6_image=$K6_IMAGE" >> "$RESULT_DIR/image-digests.txt"

# 3. 清理 Redis 缓存与重置 MySQL 状态
echo "[$(date +'%T')] 清理 Redis 缓存并重置 MySQL 状态计数器..."
docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
  "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
  exec -T redis redis-cli -n 1 flushdb >/dev/null

MYSQL_EXEC=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT"
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml"
  "${OBSERVATION_COMPOSE_FILE_ARGS[@]}"
  exec -T mysql sh -c 'mysql -uexam_runtime -p"$EXAM_DB_PASSWORD" exam_runtime'
)
MYSQL_EXEC_RAW=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT"
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml"
  "${OBSERVATION_COMPOSE_FILE_ARGS[@]}"
  exec -T mysql sh -c 'mysql -uexam_runtime -p"$EXAM_DB_PASSWORD" -N exam_runtime'
)

docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
  "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
  exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "FLUSH STATUS; TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;"' \
  >/dev/null 2>&1 || true

# 4. 准备数据
echo "[$(date +'%T')] 准备 $USERS 人压测数据 (到期时间: ${DUE_SECONDS}s)..."
SESSION_MAP_FILE="$RESULT_DIR/session-map.json"
PREPARE_OUTPUT=$(USERS="$USERS" EXAM_ID="$EXAM_ID" DUE_IN_SECONDS="$DUE_SECONDS" \
  TOKENS_FILE="$TOKEN_FILE" SESSION_MAP_FILE="$SESSION_MAP_FILE" \
  node "$SCRIPT_DIR/prepare-timeout-load-data.mjs")
echo "$PREPARE_OUTPUT" | tail -n 1 | node -e '
const line = require("node:fs").readFileSync(0, "utf8").trim();
try {
  const value = JSON.parse(line);
  delete value.tokenFile;
  process.stdout.write(JSON.stringify(value) + "\n");
} catch {
  process.stdout.write("准备数据输出不可解析\n");
}
'

PREPARE_JSON="$(printf '%s\n' "$PREPARE_OUTPUT" | tail -n 1)"
DEADLINE_EPOCH_MS="$(node -e \
  'const value=JSON.parse(process.argv[1]);process.stdout.write(String(value.deadlineEpochMs||""))' \
  "$PREPARE_JSON")"
TARGET_TASK_ID="$(node -e \
  'const value=JSON.parse(process.argv[1]);process.stdout.write(String(value.targetTaskId||""))' \
  "$PREPARE_JSON")"
if ! [[ "$DEADLINE_EPOCH_MS" =~ ^[0-9]+$ ]] || ! [[ "$TARGET_TASK_ID" =~ ^[0-9]+$ ]]; then
  echo "准备数据输出缺少合法 deadlineEpochMs 或 targetTaskId" >&2
  exit 2
fi
chmod 600 "$TOKEN_FILE"
chmod 600 "$SESSION_MAP_FILE"

# 数据准备脚本完成后才知道已核对的目标 task；更新元数据的最终版本，
# 避免把启动前的 null 当成可复核的目标任务证据。
node - "$RESULT_DIR/metadata.json" "$TARGET_TASK_ID" "$SESSION_MAP_FILE" <<'NODE'
import fs from 'node:fs'
import path from 'node:path'
const [metadataFile, targetTaskId, sessionMapFile] = process.argv.slice(2)
const metadata = JSON.parse(fs.readFileSync(metadataFile, 'utf8'))
if (!/^\d+$/.test(targetTaskId) || Number(targetTaskId) <= 0) {
  throw new Error(`targetTaskId 非法: ${targetTaskId}`)
}
metadata.targetTaskId = Number(targetTaskId)
metadata.sessionMapFile = path.basename(sessionMapFile)
metadata.dataPreparedAt = new Date().toISOString()
fs.writeFileSync(metadataFile, `${JSON.stringify(metadata, null, 2)}\n`, { mode: 0o600 })
NODE

if [ "$OBSERVATION_ENABLED" = true ]; then
  OBSERVATION_DIR="$RESULT_DIR/observation"
  EVENT_FILE="$OBSERVATION_DIR/events.ndjson"
  CLAIM_LOCK_FILE="$OBSERVATION_DIR/target-claim.lock"
  RELEASE_FILE="$OBSERVATION_DIR/release"
  STATUS_SESSION_IDS_FILE="$RESULT_DIR/diagnostic-session-ids.tsv"
  mkdir -p "$OBSERVATION_DIR"
  : > "$EVENT_FILE"
  rm -f "$CLAIM_LOCK_FILE" "$RELEASE_FILE"
  if ! [[ "$TARGET_TASK_ID" =~ ^[0-9]+$ ]] || [ "$TARGET_TASK_ID" -le 0 ]; then
    echo "无法从已核对数据中取得目标 task_id" >&2
    exit 2
  fi
if [ "$TIMEOUT_DIAGNOSTIC" = true ]; then
    SESSION_MAP_FILE="$SESSION_MAP_FILE" DIAGNOSTIC_SESSION_IDS_FILE="$STATUS_SESSION_IDS_FILE" \
      DIAGNOSTIC_SAMPLE_STRIDE="$DIAGNOSTIC_SAMPLE_STRIDE" \
      node "$SCRIPT_DIR/select-diagnostic-sessions.mjs" "$SESSION_MAP_FILE" \
      "$STATUS_SESSION_IDS_FILE" "$DIAGNOSTIC_SAMPLE_STRIDE" \
      > "$RESULT_DIR/diagnostic-session-selection.json"
    chmod 600 "$STATUS_SESSION_IDS_FILE"
else
  rm -f "$STATUS_SESSION_IDS_FILE"
fi
  cat > "$RESULT_DIR/observation/control.properties" <<CONTROL
runId=$TIMESTAMP
examId=$EXAM_ID
targetTaskId=$TARGET_TASK_ID
eventFile=/test-results/observation/events.ndjson
claimLockFile=/test-results/observation/target-claim.lock
releaseFile=/test-results/observation/release
statusSessionIdsFile=$([ "$TIMEOUT_DIAGNOSTIC" = true ] && echo /test-results/diagnostic-session-ids.tsv || true)
holdEnabled=$([ "$OBSERVATION_MODE" = hold ] && echo true || echo false)
holdTimeoutSeconds=15
CONTROL
  chmod 600 "$RESULT_DIR/observation/control.properties"
  echo "[$(date +'%T')] 控制清单已写入，目标 task=$TARGET_TASK_ID；现在启动带隔离组件的 Runtime"
  docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
    -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
    "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
    up -d --scale runtime-service="$REPLICAS" gateway runtime-service
fi

FAULT_TIMELINE_FILE="$RESULT_DIR/fault-timeline.txt"
TARGET_CONTAINER_ID=""
TARGET_CONTAINER_NAME=""
if [ "$FAULT_INJECTION" = "node_crash" ]; then
  mapfile -t runtime_container_ids < <(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
    -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
    "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
    ps -q runtime-service)
  if [ "${#runtime_container_ids[@]}" -lt 2 ]; then
    echo "node_crash 场景至少需要两个 Runtime 副本" >&2
    exit 2
  fi
  TARGET_CONTAINER_ID="${runtime_container_ids[-1]}"
  FAULT_TARGET_INSTANCE_ID="$(docker inspect --format '{{.Config.Hostname}}' "$TARGET_CONTAINER_ID")"
  TARGET_CONTAINER_NAME=$(docker inspect --format '{{.Name}}' "$TARGET_CONTAINER_ID" | sed 's#^/##')
  {
    echo "fault=node_crash"
    echo "exam_id=$EXAM_ID"
    echo "shard_total_expected=$REPLICAS"
    echo "target_container=$TARGET_CONTAINER_NAME"
    echo "survivor_pause_count=0"
    echo "target_selection_at=$(date --iso-8601=seconds)"
    echo "note=不预先暂停存活实例；使用目标实例 worker.active 与 PROCESSING 同时确认在途现场"
  } > "$FAULT_TIMELINE_FILE"
  echo "[$(date +'%T')] [故障准备] 目标实例为 $TARGET_CONTAINER_NAME；不暂停其他存活副本"
fi
if [ "$OBSERVATION_MODE" = hold ]; then
  {
    echo "fault=deterministic-observation"
    echo "exam_id=$EXAM_ID"
    echo "shard_total_expected=$REPLICAS"
    echo "kill_on=$OBSERVATION_KILL_ON"
    echo "target_task_id=$TARGET_TASK_ID"
    echo "target_selection_at=$(date --iso-8601=seconds)"
    echo "note=只杀死观察事件对应实例；不暂停或自动重建存活实例"
  } > "$FAULT_TIMELINE_FILE"
fi

SAMPLER_STATE_DIR="$RESULT_DIR/sampling"
mkdir -p "$SAMPLER_STATE_DIR"
RESOURCE_TIMESERIES_FILE="$RESULT_DIR/container-resource-timeseries.tsv"
REGISTRY_TIMELINE="$RESULT_DIR/registry-timeline.tsv"
PROM_METRICS_FILE="$RESULT_DIR/runtime-prometheus-samples.log"
EXPECTED_INSTANCES_FILE="$SAMPLER_STATE_DIR/expected-instances.tsv"
docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
  "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" \
  ps -q runtime-service | while read -r container_id; do
    [ -n "$container_id" ] || continue
    instance_id="$(docker inspect --format '{{.Config.Hostname}}' "$container_id")"
    container_name="$(docker inspect --format '{{.Name}}' "$container_id" | sed 's#^/##')"
    printf '%s\t%s\t%s\n' "$container_id" "$instance_id" "$container_name"
  done > "$EXPECTED_INSTANCES_FILE"
if [ ! -s "$EXPECTED_INSTANCES_FILE" ]; then
  echo "无法建立本轮固定 Runtime 实例集合" >&2
  exit 2
fi

# 采样器输出保持既有文件名；状态文件和同名软链接用于独立完整性校验。
ln -s ../container-resource-timeseries.tsv "$SAMPLER_STATE_DIR/resource.samples"
ln -s ../registry-timeline.tsv "$SAMPLER_STATE_DIR/registry.samples"
ln -s ../runtime-prometheus-samples.log "$SAMPLER_STATE_DIR/prometheus.samples"

start_process_group RESOURCE_SAMPLER_PID /dev/null "$RESULT_DIR/resource-sampler.log" \
  bash "$SCRIPT_DIR/timeout-sampler.sh" resource "$RESOURCE_TIMESERIES_FILE" \
  "$SAMPLER_STATE_DIR/resource.state.json" "" "$EXPECTED_INSTANCES_FILE"
start_process_group REGISTRY_SAMPLER_PID /dev/null "$RESULT_DIR/registry-sampler.log" \
  bash "$SCRIPT_DIR/timeout-sampler.sh" registry "$REGISTRY_TIMELINE" \
  "$SAMPLER_STATE_DIR/registry.state.json" "" "$EXPECTED_INSTANCES_FILE"
if [ "${PROMETHEUS_SAMPLING:-true}" = "true" ]; then
  start_process_group PROM_SAMPLER_PID /dev/null "$RESULT_DIR/prometheus-sampler.log" \
    bash "$SCRIPT_DIR/timeout-sampler.sh" prometheus "$PROM_METRICS_FILE" \
    "$SAMPLER_STATE_DIR/prometheus.state.json" "$TOKEN_FILE" "$EXPECTED_INSTANCES_FILE"
else
  cat > "$SAMPLER_STATE_DIR/prometheus.state.json" <<'STATE'
{"mode":"prometheus","started":false,"ready":false,"stopped":true,"validSamples":0,"invalidSamples":0,"errorCount":0,"hasHikari":false,"hasJvm":false,"hasBacklog":false}
STATE
  : > "$PROM_METRICS_FILE"
fi

wait_sampler_ready() {
  local name="$1"
  local state_file="$2"
  local deadline=$((SECONDS + ${SAMPLER_READY_TIMEOUT_SECONDS:-30}))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ -s "$state_file" ] && [ "$(jq -r '.ready // false' "$state_file" 2>/dev/null || echo false)" = "true" ]; then
      return 0
    fi
    if [ -s "$state_file" ] && [ "$(jq -r '.stopped // false' "$state_file" 2>/dev/null || echo false)" = "true" ]; then
      break
    fi
    sleep 1
  done
  echo "[工具错误] $name 采样器未在就绪窗口内产生有效样本" >&2
  return 1
}

SAMPLER_PRECHECK_FAILED=0
wait_sampler_ready resource "$SAMPLER_STATE_DIR/resource.state.json" || SAMPLER_PRECHECK_FAILED=1
wait_sampler_ready registry "$SAMPLER_STATE_DIR/registry.state.json" || SAMPLER_PRECHECK_FAILED=1
wait_sampler_ready prometheus "$SAMPLER_STATE_DIR/prometheus.state.json" || SAMPLER_PRECHECK_FAILED=1
if [ "$SAMPLER_PRECHECK_FAILED" -ne 0 ]; then
  echo "[FATAL] 认证或采样预检失败，停止在正式 k6 负载之前" >&2
  exit 2
fi

K6_SUMMARY_FILE="$RESULT_DIR/k6-summary.json"
K6_CID_FILE="$RESULT_DIR/k6.cid"
K6_CONTAINER_NAME="timeout-k6-${TIMESTAMP}-${BASHPID}"
if [ "$ENABLE_POLLING" = "true" ]; then
  REMAINING_SECS=$(node -e "console.log(Math.max(0, Math.ceil(($DEADLINE_EPOCH_MS - Date.now())/1000)))")
  echo "[$(date +'%T')] 距离考试截止还有 ${REMAINING_SECS} 秒，后台启动 k6 轮询（与故障注入并发运行）..."

  K6_DIAGNOSTIC_ENV=(-e TIMEOUT_DIAGNOSTIC="$TIMEOUT_DIAGNOSTIC"
    -e DIAGNOSTIC_SAMPLE_STRIDE="$DIAGNOSTIC_SAMPLE_STRIDE")
  K6_DIAGNOSTIC_ARGS=()
  if [ "$TIMEOUT_DIAGNOSTIC" = true ]; then
    K6_DIAGNOSTIC_ENV+=(-e SESSION_MAP_FILE=/results/session-map.json)
    K6_DIAGNOSTIC_ARGS=(--out json=/results/client-diagnostics.ndjson)
  fi

  K6_CMD=(docker run --name "$K6_CONTAINER_NAME" --cidfile "$K6_CID_FILE" \
    --user "$(id -u):$(id -g)" --network host --ulimit nofile=1048576:1048576
    -v "$LOAD_TEST_ROOT:/load-test"
    -v "$RESULT_DIR:/results"
    -v "$TOKEN_FILE:/run/secrets/timeout-tokens.json:ro"
    -e BASE_URL="http://localhost:16730/api/v1"
    -e EXAM_ID="$EXAM_ID"
    -e DEADLINE_EPOCH_MS="$DEADLINE_EPOCH_MS"
    -e TOKENS_FILE="/run/secrets/timeout-tokens.json"
    -e USERS="$USERS"
    -e POLL_BASE_MS="$POLL_BASE_MS"
    -e POLL_MAX_MS="$POLL_MAX_MS"
    -e LOAD_FLOW="$LOAD_FLOW"
    -e DB_P99_GATE_MS="$(awk -v seconds="$DB_P99_GATE_SECONDS" 'BEGIN { printf "%.0f", seconds * 1000 }')"
    -e DB_MAX_GATE_MS="$(awk -v seconds="$DB_MAX_GATE_SECONDS" 'BEGIN { printf "%.0f", seconds * 1000 }')"
    "${K6_DIAGNOSTIC_ENV[@]}"
    "$K6_IMAGE" run \
      "${K6_DIAGNOSTIC_ARGS[@]}" \
      --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
      --summary-export="/results/k6-summary.json" /load-test/scenarios/timeout-submission.js
  )

  start_process_group K6_PID "$RESULT_DIR/k6-stdout.log" "$RESULT_DIR/k6-stderr.log" "${K6_CMD[@]}"
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
FAULT_INJECTION_VALID=false
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

if [ "$OBSERVATION_MODE" = hold ]; then
  observation_event() {
    local event_type="$1"
    node - "$EVENT_FILE" "$event_type" "$EXAM_ID" "$TARGET_TASK_ID" <<'NODE'
import fs from 'node:fs'
const [file, eventType, examId, taskId] = process.argv.slice(2)
let selected = null
if (fs.existsSync(file)) {
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue
    try {
      const value = JSON.parse(line)
      if (value.runId && value.eventType === eventType
        && String(value.examId) === examId && String(value.taskId) === taskId
        && value.firstTargetClaim === true) {
        selected = value
      }
    } catch {}
  }
}
if (selected) process.stdout.write(JSON.stringify(selected))
NODE
  }
  instance_container() {
    local instance_id="$1"
    local container_id hostname
    while read -r container_id; do
      [ -n "$container_id" ] || continue
      hostname="$(docker inspect --format '{{.Config.Hostname}}' "$container_id" 2>/dev/null || true)"
      if [ "$hostname" = "$instance_id" ]; then
        printf '%s\n' "$container_id"
        return 0
      fi
    done < <(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
      -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
      "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" ps -q runtime-service)
    return 1
  }

  observation_value() {
    local json_value="$1"
    local key="$2"
    printf '%s' "$json_value" | node -e '
const key = process.argv[1]
let text = ""
process.stdin.on("data", chunk => { text += chunk })
process.stdin.on("end", () => {
  try {
    const value = JSON.parse(text)[key]
    if (value != null) process.stdout.write(String(value))
  } catch {}
})
' "$key"
  }

  wait_for_observation_event() {
    local event_type="$1"
    local wait_started
    local event_json
    wait_started=$(date +%s)
    while true; do
      event_json="$(observation_event "$event_type" || true)"
      if [ -n "$event_json" ]; then
        printf '%s' "$event_json"
        return 0
      fi
      if [ "$(( $(date +%s) - wait_started ))" -ge "$OBSERVATION_WAIT_TIMEOUT_SECONDS" ]; then
        echo "[$(date +'%T')] [确定性接管] 未在 ${OBSERVATION_WAIT_TIMEOUT_SECONDS}s 内得到 $event_type" >&2
        return 1
      fi
      if grep -q '"eventType":"HOLD_EXPIRED"' "$EVENT_FILE" 2>/dev/null; then
        echo "[$(date +'%T')] [确定性接管] 观察点自动释放，注入条件未满足" >&2
        return 1
      fi
      sleep 0.2
    done
  }

  # 续租场景必须先保存 CLAIM_HELD 时刻的数据库租约，再等待
  # RENEW_SUCCEEDED；否则“初始租约”会被错误地读成续租后的值。
  echo "[$(date +'%T')] [确定性接管] 等待 CLAIM_HELD (task=$TARGET_TASK_ID)..."
  CLAIM_HELD_JSON="$(wait_for_observation_event CLAIM_HELD || true)"
  if [ -z "$CLAIM_HELD_JSON" ]; then
    echo "injection_condition=NOT_MET" >> "$FAULT_TIMELINE_FILE"
    FAULT_INJECTION_FAILED=1
  fi

  if [ "$FAULT_INJECTION_FAILED" -eq 0 ]; then
    OBSERVATION_INSTANCE_ID="$(observation_value "$CLAIM_HELD_JSON" instanceId)"
    OBSERVATION_TOKEN_FINGERPRINT="$(observation_value "$CLAIM_HELD_JSON" claimTokenFingerprint)"
    OBSERVATION_ATTEMPT="$(observation_value "$CLAIM_HELD_JSON" attemptCount)"
    CLAIM_HELD_AT="$(observation_value "$CLAIM_HELD_JSON" wallClockEpochMs)"
    TARGET_CONTAINER_ID="$(instance_container "$OBSERVATION_INSTANCE_ID" || true)"
    if [ -z "$TARGET_CONTAINER_ID" ]; then
      echo "无法把 observation instanceId 映射到真实容器 ID" >&2
      FAULT_INJECTION_FAILED=1
    else
      FAULT_TARGET_INSTANCE_ID="$OBSERVATION_INSTANCE_ID"
      TARGET_CONTAINER_NAME="$(docker inspect --format '{{.Name}}' "$TARGET_CONTAINER_ID" | sed 's#^/##')"
      CLAIM_ROW="$(echo "select status,attempt_count,lower(substr(sha2(claim_token,256),1,16)),round(unix_timestamp(lease_until)*1000) from submission_timeout_task where id=$TARGET_TASK_ID;" | "${MYSQL_EXEC_RAW[@]}")"
      CLAIM_STATUS="$(printf '%s' "$CLAIM_ROW" | awk '{print $1}')"
      CLAIM_ATTEMPT="$(printf '%s' "$CLAIM_ROW" | awk '{print $2}')"
      CLAIM_DB_FINGERPRINT="$(printf '%s' "$CLAIM_ROW" | awk '{print $3}')"
      INITIAL_LEASE_EPOCH_MS="$(printf '%s' "$CLAIM_ROW" | awk '{print $4}')"
      if [ "$CLAIM_STATUS" != PROCESSING ] || [ "$CLAIM_ATTEMPT" != "$OBSERVATION_ATTEMPT" ] \
        || [ "$CLAIM_DB_FINGERPRINT" != "$OBSERVATION_TOKEN_FINGERPRINT" ]; then
        echo "目标任务数据库现场与观察事件不一致: status=$CLAIM_STATUS attempt=$CLAIM_ATTEMPT fingerprint=$CLAIM_DB_FINGERPRINT" >&2
        FAULT_INJECTION_FAILED=1
      fi
    fi
  fi

  required_event="$OBSERVATION_KILL_ON"
  RENEWED_LEASE_EPOCH_MS="NA"
  RENEW_SUCCEEDED_AT=""
  if [ "$FAULT_INJECTION_FAILED" -eq 0 ] && [ "$required_event" = RENEW_SUCCEEDED ]; then
    echo "[$(date +'%T')] [确定性接管] 等待 RENEW_SUCCEEDED (task=$TARGET_TASK_ID)..."
    RENEWED_EVENT_JSON="$(wait_for_observation_event RENEW_SUCCEEDED || true)"
    if [ -z "$RENEWED_EVENT_JSON" ]; then
      echo "injection_condition=NOT_MET" >> "$FAULT_TIMELINE_FILE"
      FAULT_INJECTION_FAILED=1
    else
      RENEW_SUCCEEDED_AT="$(observation_value "$RENEWED_EVENT_JSON" wallClockEpochMs)"
      RENEWED_LEASE_EPOCH_MS="$(echo "select round(unix_timestamp(lease_until)*1000) from submission_timeout_task where id=$TARGET_TASK_ID;" | "${MYSQL_EXEC_RAW[@]}")"
      if ! [[ "$RENEWED_LEASE_EPOCH_MS" =~ ^[0-9]+$ ]] \
        || ! [[ "$INITIAL_LEASE_EPOCH_MS" =~ ^[0-9]+$ ]] \
        || [ "$RENEWED_LEASE_EPOCH_MS" -le "$INITIAL_LEASE_EPOCH_MS" ]; then
        echo "续租事件后 lease_until 未比 CLAIM_HELD 时的初始值延长" >&2
        FAULT_INJECTION_FAILED=1
      fi
    fi
  fi

  if [ "$FAULT_INJECTION_FAILED" -eq 0 ]; then
    FAULT_INFLIGHT_IDS="$TARGET_TASK_ID"
    FAULT_INFLIGHT_COUNT=1
    DB_INJECTION_TIME="$(echo 'select current_timestamp(3);' | "${MYSQL_EXEC_RAW[@]}")"
    REGISTRY_COUNT_AT_KILL="$(echo 'select count(*) from xxl_job_registry where registry_key=0x6578616d2d72756e74696d652d6578656375746f72;' | docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" "${OBSERVATION_COMPOSE_FILE_ARGS[@]}" exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N xxl_job' | tr -d '[:space:]')"
    {
      echo "database_injection_time=$DB_INJECTION_TIME"
      echo "observation_instance_id=$OBSERVATION_INSTANCE_ID"
      echo "target_container=$TARGET_CONTAINER_NAME"
      echo "target_task_id=$TARGET_TASK_ID"
      echo "claim_attempt=$OBSERVATION_ATTEMPT"
      echo "claim_token_fingerprint=$OBSERVATION_TOKEN_FINGERPRINT"
      echo "claim_held_at_epoch_ms=$CLAIM_HELD_AT"
      echo "initial_lease_epoch_ms=$INITIAL_LEASE_EPOCH_MS"
      echo "renew_succeeded_at_epoch_ms=$RENEW_SUCCEEDED_AT"
      echo "renewed_lease_epoch_ms=$RENEWED_LEASE_EPOCH_MS"
      echo "registry_count_at_kill=$REGISTRY_COUNT_AT_KILL"
      echo "kill_on=$OBSERVATION_KILL_ON"
      echo "kill_started_at=$(date --iso-8601=seconds)"
      echo "survivors_paused=false"
      echo "injection_condition=met"
    } >> "$FAULT_TIMELINE_FILE"
    echo "[$(date +'%T')] [确定性接管] $required_event 已核对，强制杀死 $TARGET_CONTAINER_NAME"
    if docker kill "$TARGET_CONTAINER_ID" >/dev/null; then
      FAULT_INJECTION_VALID=true
      echo "killed_at=$(date --iso-8601=seconds)" >> "$FAULT_TIMELINE_FILE"
    else
      echo "docker kill 执行失败" >&2
      FAULT_INJECTION_FAILED=1
    fi
  fi

  echo "survivors_untouched=true" >> "$FAULT_TIMELINE_FILE"
fi

if [ "$FAULT_INJECTION" = "node_crash" ]; then
  TARGET_PROM_TOKEN="$(node - "$TOKEN_FILE" <<'NODE'
import fs from 'node:fs'
const tokens = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
if (!Array.isArray(tokens) || typeof tokens[0] !== 'string' || tokens[0].length === 0) process.exit(2)
process.stdout.write(tokens[0])
NODE
)" || TARGET_PROM_TOKEN=""
  target_worker_active() {
    local ip response
    ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$TARGET_CONTAINER_ID" 2>/dev/null || true)"
    [ -n "$ip" ] || return 1
    response="$(curl -fsS --max-time 2 -H "Authorization: Bearer $TARGET_PROM_TOKEN" \
      "http://$ip:16735/actuator/prometheus" 2>/dev/null || true)"
    printf '%s\n' "$response" | awk '/^exam_timeout_submission_worker_active([[:space:]]|\{)/ {print $NF; exit}'
  }

  echo "[$(date +'%T')] [故障注入] 等待目标实例有 worker.active 且数据库出现 PROCESSING 任务..."
  fault_wait_started=$(date +%s)
  while true; do
    candidate_ids=$(echo "SELECT GROUP_CONCAT(id ORDER BY id) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='PROCESSING';" | "${MYSQL_EXEC_RAW[@]}")
    TARGET_ACTIVE_WORKERS="$(target_worker_active || true)"
    if [ -n "$candidate_ids" ] && [ "$candidate_ids" != "NULL" ] \
      && [ -n "$TARGET_ACTIVE_WORKERS" ] \
      && awk -v value="$TARGET_ACTIVE_WORKERS" 'BEGIN {exit !(value > 0)}'; then
      FAULT_INFLIGHT_IDS="$candidate_ids"
      FAULT_INFLIGHT_COUNT=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='PROCESSING';" | "${MYSQL_EXEC_RAW[@]}")
      break
    fi
    if [ "$(( $(date +%s) - fault_wait_started ))" -ge "$FAULT_WAIT_TIMEOUT_SECONDS" ]; then
      echo "[$(date +'%T')] [故障注入] 未在 ${FAULT_WAIT_TIMEOUT_SECONDS}s 内确认目标实例在途任务"
      FAULT_INJECTION_FAILED=1
      break
    fi
    sleep 0.2
  done

  if [ "$FAULT_INJECTION_FAILED" -eq 0 ] && [ -n "$TARGET_CONTAINER_ID" ]; then
    DB_INJECTION_TIME=$(echo "SELECT CURRENT_TIMESTAMP(3);" | "${MYSQL_EXEC_RAW[@]}")
    {
      echo "database_injection_time=$DB_INJECTION_TIME"
      echo "processing_count_before_kill=$FAULT_INFLIGHT_COUNT"
      echo "processing_task_ids_before_kill=$FAULT_INFLIGHT_IDS"
      echo "target_worker_active_before_kill=$TARGET_ACTIVE_WORKERS"
      echo "survivors_paused=false"
    } >> "$FAULT_TIMELINE_FILE"
    echo "[$(date +'%T')] [故障注入] PROCESSING=$FAULT_INFLIGHT_COUNT，目标 worker.active=$TARGET_ACTIVE_WORKERS，强制杀死 $TARGET_CONTAINER_NAME"
    if docker kill "$TARGET_CONTAINER_ID" >/dev/null; then
      FAULT_INJECTION_VALID=true
    else
      echo "[$(date +'%T')] [故障注入] docker kill 执行失败"
      FAULT_INJECTION_FAILED=1
    fi
  fi
  echo "survivors_untouched=true" >> "$FAULT_TIMELINE_FILE"
fi

wait_until_epoch "$((DEADLINE_SEC + 30))"
DONE_COUNT_30=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE' AND completed_at <= timestampadd(second,30,due_at);" | "${MYSQL_EXEC_RAW[@]}")
DONE_WALL_30=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE';" | "${MYSQL_EXEC_RAW[@]}")
echo "[$(date +'%T')] [Checkpoint 30s] 30 秒窗口内完成(due_at+30s): $DONE_COUNT_30 / $USERS (墙钟已完成为诊断参考: $DONE_WALL_30)"

# 截止后 60 秒停止等待并断言
echo "[$(date +'%T')] 等待截止后 60 秒最终收敛截止线..."
wait_until_epoch "$((DEADLINE_SEC + 60))"
DONE_COUNT_60=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE' AND completed_at <= timestampadd(second,60,due_at);" | "${MYSQL_EXEC_RAW[@]}")
DONE_WALL_60=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE';" | "${MYSQL_EXEC_RAW[@]}")
echo "[$(date +'%T')] [Checkpoint 60s] 60 秒窗口内完成(due_at+60s): $DONE_COUNT_60 / $USERS (墙钟已完成为诊断参考: $DONE_WALL_60)"

if [ "$OBSERVATION_MODE" = hold ]; then
  echo "[$(date +'%T')] [确定性接管] 继续观察到 due+${OBSERVATION_LIMIT_SECONDS}s，不提前重建故障实例..."
  wait_until_epoch "$((DEADLINE_SEC + OBSERVATION_LIMIT_SECONDS))"
  DONE_WALL_OBSERVATION=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE';" | "${MYSQL_EXEC_RAW[@]}")
  DONE_AT_OBSERVATION=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE' AND completed_at <= timestampadd(second,$OBSERVATION_LIMIT_SECONDS,due_at);" | "${MYSQL_EXEC_RAW[@]}")
  echo "[$(date +'%T')] [确定性接管] 观察终态完成=$DONE_WALL_OBSERVATION，窗口内完成=$DONE_AT_OBSERVATION"
  {
    echo "done_at_observation_limit=$DONE_AT_OBSERVATION"
    echo "done_wall_at_observation_limit=$DONE_WALL_OBSERVATION"
  } >> "$FAULT_TIMELINE_FILE"
fi

if { [ "$FAULT_INJECTION" = "node_crash" ] || [ "$OBSERVATION_MODE" = hold ]; } \
  && [ -n "$FAULT_INFLIGHT_IDS" ]; then
  FAULT_RECOVERED_INFLIGHT=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND id IN ($FAULT_INFLIGHT_IDS) AND status='DONE' AND attempt_count>1;" | "${MYSQL_EXEC_RAW[@]}")
  {
    echo "recovered_inflight_tasks=$FAULT_RECOVERED_INFLIGHT"
    echo "done_at_30s=$DONE_COUNT_30"
    echo "done_at_60s=$DONE_COUNT_60"
  } >> "$FAULT_TIMELINE_FILE"
fi

# 6. 先等待 k6，再停止并回收所有采样器
K6_CONTAINER_COMPLETED=true
if [ "$ENABLE_POLLING" = "true" ]; then
  if [ -n "$K6_PID" ]; then
    echo "[$(date +'%T')] 等待 k6 客户端场景结束并保存真实退出码..."
    if wait_process "$K6_PID"; then
      K6_EXIT_CODE=0
    else
      K6_EXIT_CODE=$?
    fi
    # wait 完成后才清空 PID；清理函数不会误把尚未等待的 k6 当成成功。
    K6_PID=""
    K6_WAITED=true
  fi
  K6_CONTAINER_STATE="unknown"
  if docker inspect "$K6_CONTAINER_NAME" >/dev/null 2>&1; then
    K6_CONTAINER_STATE="$(docker inspect --format '{{.State.Status}}' "$K6_CONTAINER_NAME" 2>/dev/null || echo unknown)"
  fi
  if [ "$K6_CONTAINER_STATE" != "exited" ]; then
    K6_CONTAINER_COMPLETED=false
    echo "[工具错误] k6 CLI 已结束但本轮容器状态不是 exited: $K6_CONTAINER_STATE" >&2
  fi
  echo "k6_exit_code=$K6_EXIT_CODE" | tee "$RESULT_DIR/k6-exit-status.txt"
else
  K6_EXIT_CODE="NOT_RUN"
  echo "k6_exit_code=NOT_RUN" > "$RESULT_DIR/k6-exit-status.txt"
fi

stop_managed_process REGISTRY_SAMPLER_PID
stop_managed_process PROM_SAMPLER_PID
stop_managed_process RESOURCE_SAMPLER_PID

SAMPLER_FILES_STABLE=true
for sampler_file in "$RESOURCE_TIMESERIES_FILE" "$REGISTRY_TIMELINE" "$PROM_METRICS_FILE"; do
  if [ ! -e "$sampler_file" ]; then
    SAMPLER_FILES_STABLE=false
    continue
  fi
  before_size="$(stat -c '%s' "$sampler_file" 2>/dev/null || echo -1)"
  sleep 0.2
  after_size="$(stat -c '%s' "$sampler_file" 2>/dev/null || echo -1)"
  if [ "$before_size" != "$after_size" ]; then SAMPLER_FILES_STABLE=false; fi
done
NO_RESIDUAL_PROCESSES=true
[ "$SAMPLER_STOP_ERROR" -eq 0 ] || NO_RESIDUAL_PROCESSES=false

SAMPLING_VALIDATION_EXIT=0
FAULT_TARGET_VALIDATION_ARGS=()
if [ "$FAULT_INJECTION" = "node_crash" ] || [ "$OBSERVATION_MODE" = hold ]; then
  FAULT_KILLED_AT_MS=""
  if [ -f "$FAULT_TIMELINE_FILE" ] && grep -q '^killed_at=' "$FAULT_TIMELINE_FILE"; then
    FAULT_KILLED_AT_MS="$(date -d "$(sed -n 's/^killed_at=//p' "$FAULT_TIMELINE_FILE" | tail -n 1)" +%s%3N 2>/dev/null || true)"
  fi
  FAULT_TARGET_VALIDATION_ARGS=(--fault-target true --fault-target-instance "$FAULT_TARGET_INSTANCE_ID")
  [ -n "$FAULT_KILLED_AT_MS" ] && FAULT_TARGET_VALIDATION_ARGS+=(--fault-killed-at-ms "$FAULT_KILLED_AT_MS")
else
  FAULT_TARGET_VALIDATION_ARGS=(--fault-target false)
fi
node "$SCRIPT_DIR/validate-sampling.mjs" \
  --state-dir "$SAMPLER_STATE_DIR" \
  --output "$RESULT_DIR/sampling-state.json" \
  --no-residual-processes "$NO_RESIDUAL_PROCESSES" \
  --files-stable "$SAMPLER_FILES_STABLE" \
  "${FAULT_TARGET_VALIDATION_ARGS[@]}" \
  > "$RESULT_DIR/sampling-completeness.txt" 2>&1 || SAMPLING_VALIDATION_EXIT=$?
LEAK_FILES=$(grep -rl -E 'BEGIN PRIVATE KEY|eyJhbGciOiJI' "$RESULT_DIR" 2>/dev/null | head -3 || true)
if [ -n "$LEAK_FILES" ]; then
  echo "secret-scan=ERROR:结果目录发现敏感数据文件（未输出内容）" >> "$RESULT_DIR/sampling-completeness.txt"
  node - "$RESULT_DIR/sampling-state.json" <<'NODE'
import fs from 'node:fs'
const file = process.argv[2]
try {
  const value = JSON.parse(fs.readFileSync(file, 'utf8'))
  value.status = 'ERROR'
  value.errors = [...(value.errors || []), '结果目录发现敏感数据']
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 })
} catch {}
NODE
else
  echo "secret-scan=CLEAN" >> "$RESULT_DIR/sampling-completeness.txt"
fi
cat "$RESULT_DIR/sampling-completeness.txt"

K6_PARSE_EXIT=0
if [ "$ENABLE_POLLING" = "true" ]; then
  node "$SCRIPT_DIR/parse-k6-summary.mjs" \
    --input "$K6_SUMMARY_FILE" \
    --output "$RESULT_DIR/k6-parsed.json" \
    --expected-iterations "$USERS" \
    > "$RESULT_DIR/k6-parse.log" 2>&1 || K6_PARSE_EXIT=$?
else
  cat > "$RESULT_DIR/k6-parsed.json" <<'JSON'
{"status":"NOT_RUN","errors":[],"metrics":null,"thresholds":null}
JSON
fi

DIAGNOSTIC_EXTRACT_EXIT=0
if [ "$TIMEOUT_DIAGNOSTIC" = "true" ]; then
  node "$SCRIPT_DIR/extract-timeout-diagnostics.mjs" \
    --input "$RESULT_DIR/client-diagnostics.ndjson" \
    --session-map "$SESSION_MAP_FILE" \
    --output "$RESULT_DIR/client-diagnostics.json" \
    --stride "$DIAGNOSTIC_SAMPLE_STRIDE" \
    > "$RESULT_DIR/client-diagnostics.log" 2>&1 || DIAGNOSTIC_EXTRACT_EXIT=$?
else
  cat > "$RESULT_DIR/client-diagnostics.json" <<'JSON'
{"status":"NOT_RUN","errors":[],"warnings":[],"sessions":{}}
JSON
fi

# 7. 查询并保存 MySQL 权威统计与窗口函数延迟
echo ""
echo "[$(date +'%T')] 查询 MySQL 权威统计指标并保存报告..."

# 用一次 MySQL 往返测量数据库时钟相对负载机时钟的偏差。结果包含 RTT
# 一半作为不确定性范围；没有这个范围时不做精确毫秒归因。
CLOCK_PROBE_START_MS="$(date +%s%3N)"
DB_CLOCK_EPOCH_MS="$(echo 'select round(unix_timestamp(current_timestamp(3))*1000);' | "${MYSQL_EXEC_RAW[@]}" | tr -d '[:space:]')"
CLOCK_PROBE_END_MS="$(date +%s%3N)"
node - "$RESULT_DIR/clock-skew.json" "$CLOCK_PROBE_START_MS" "$CLOCK_PROBE_END_MS" "$DB_CLOCK_EPOCH_MS" <<'NODE'
import fs from 'node:fs'
const [output, startText, endText, dbText] = process.argv.slice(2)
const loadStartMs = Number(startText)
const loadEndMs = Number(endText)
const dbEpochMs = Number(dbText)
const roundTripMs = loadEndMs - loadStartMs
const valid = [loadStartMs, loadEndMs, dbEpochMs, roundTripMs].every(Number.isFinite)
const midpointMs = (loadStartMs + loadEndMs) / 2
const value = {
  status: valid && roundTripMs >= 0 ? 'OK' : 'ERROR',
  method: 'single_mysql_current_timestamp_round_trip',
  loadClockStartEpochMs: valid ? loadStartMs : null,
  loadClockEndEpochMs: valid ? loadEndMs : null,
  databaseClockEpochMs: valid ? dbEpochMs : null,
  roundTripMs: valid ? roundTripMs : null,
  offsetEstimateMs: valid ? dbEpochMs - midpointMs : null,
  uncertaintyMs: valid ? Math.ceil(roundTripMs / 2) : null,
  note: 'completed_at 是数据库记录时间；偏差范围不能替代事务提交时间'
}
fs.writeFileSync(output, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 })
if (value.status !== 'OK') process.exitCode = 2
NODE

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

# 诊断会话的数据库权威时间线与客户端诊断使用同一份已核对映射。
if [ "$TIMEOUT_DIAGNOSTIC" = "true" ]; then
  DIAGNOSTIC_DB_TIMELINE="$RESULT_DIR/diagnostic-db-session-timeline.tsv"
  DIAGNOSTIC_SESSION_IDS_SQL=$(node - "$SESSION_MAP_FILE" "$DIAGNOSTIC_SAMPLE_STRIDE" <<'NODE'
import fs from 'node:fs'
const [mapFile, strideText] = process.argv.slice(2)
const stride = Number(strideText)
const map = JSON.parse(fs.readFileSync(mapFile, 'utf8'))
const ids = map.entries
  .filter(entry => entry.iteration % stride === 0)
  .map(entry => String(entry.sessionId))
if (ids.length === 0 || ids.some(id => !/^\d+$/.test(id))) throw new Error('诊断会话映射为空或含非法 session_id')
process.stdout.write(ids.join(','))
NODE
)
  {
    printf 'session_id\ttask_id\tdue_at_epoch_ms\tcompleted_at_epoch_ms\tattempt_count\ttask_status\tsession_status\tsubmission_id\n'
    echo "select task.session_id,task.id,round(unix_timestamp(task.due_at)*1000),round(unix_timestamp(task.completed_at)*1000),task.attempt_count,task.status,session.status,task.submission_id
      from submission_timeout_task task join exam_session session on session.id=task.session_id
     where task.exam_id=$EXAM_ID and task.session_id in ($DIAGNOSTIC_SESSION_IDS_SQL)
     order by task.session_id;" | "${MYSQL_EXEC[@]}"
  } > "$DIAGNOSTIC_DB_TIMELINE"
  chmod 600 "$DIAGNOSTIC_DB_TIMELINE"
  node "$SCRIPT_DIR/analyze-timeout-diagnostics.mjs" \
    --client "$RESULT_DIR/client-diagnostics.json" \
    --database "$DIAGNOSTIC_DB_TIMELINE" \
    --events "$EVENT_FILE" \
    --session-map "$SESSION_MAP_FILE" \
    --stride "$DIAGNOSTIC_SAMPLE_STRIDE" \
    --clock-skew "$RESULT_DIR/clock-skew.json" \
  --output "$RESULT_DIR/diagnostic-analysis.json" \
  > "$RESULT_DIR/diagnostic-analysis.log" 2>&1 || DIAGNOSTIC_EXTRACT_EXIT=$?
else
  cat > "$RESULT_DIR/diagnostic-analysis.json" <<'JSON'
{"status":"NOT_RUN","errors":[],"sessions":{},"summary":null}
JSON
fi

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
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
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
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
  exec -T redis redis-cli -n 1 dbsize >> "$RESULT_DIR/resource-summary.txt"

docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml" \
  logs --no-color --since "$RUN_STARTED_AT" runtime-service 2>&1 \
  | grep -E 'Timeout submission initialized|Timeout submission round finished|Timeout submission retry scheduled|超时交卷批次超过任务总预算|projection|post-commit|reconciliation' \
  > "$RESULT_DIR/runtime-events.log" || true

TAKEOVER_EVIDENCE_EXIT=0
if [ "$OBSERVATION_MODE" = "hold" ]; then
  TAKEOVER_DB_EVIDENCE="$RESULT_DIR/takeover-db-evidence.tsv"
  {
    printf 'task_id\tattempt_count\ttask_status\tsubmission_id\tfinal_payload_count\tlogical_event_count\tunique_final_payload_count\tunique_logical_event_count\n'
    echo "select task.id,task.attempt_count,task.status,task.submission_id,
      (select count(*) from submission_final_payload where submission_id=task.submission_id),
      (select count(*) from outbox_event where aggregate_type='SUBMISSION' and event_type='SubmissionAccepted' and aggregate_id=cast(task.submission_id as char)),
      (select count(distinct id) from submission_final_payload where submission_id=task.submission_id),
      (select count(distinct event_type) from outbox_event where aggregate_type='SUBMISSION' and event_type='SubmissionAccepted' and aggregate_id=cast(task.submission_id as char))
      from submission_timeout_task task where task.id=$TARGET_TASK_ID;" | "${MYSQL_EXEC[@]}"
  } > "$TAKEOVER_DB_EVIDENCE"
  chmod 600 "$TAKEOVER_DB_EVIDENCE"
  node "$SCRIPT_DIR/verify-timeout-takeover.mjs" \
    --events "$EVENT_FILE" \
    --db-evidence "$TAKEOVER_DB_EVIDENCE" \
    --fault-timeline "$FAULT_TIMELINE_FILE" \
    --runtime-events "$RESULT_DIR/runtime-events.log" \
    --output "$RESULT_DIR/takeover-evidence.json" \
    > "$RESULT_DIR/takeover-evidence.log" 2>&1 || TAKEOVER_EVIDENCE_EXIT=$?
else
  cat > "$RESULT_DIR/takeover-evidence.json" <<'JSON'
{"status":"NOT_RUN","errors":[],"warnings":[],"takeover":null}
JSON
fi

# 9. 机器可读门禁判定
RETRY_COUNT=$(echo "SELECT COUNT(*) FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND attempt_count > 1;" | "${MYSQL_EXEC_RAW[@]}")
GATE_RESULT_FILE="$RESULT_DIR/gate-result.json"
GATE_EVAL_CODE=2

echo "================================================================"
echo "                   自动化上线闸门检查 (GATE CHECK)               "
echo "================================================================"
if node "$SCRIPT_DIR/evaluate-timeout-gates.mjs" \
  --metadata "$RESULT_DIR/metadata.json" \
  --database-summary "$RESULT_DIR/database-summary.txt" \
  --sampling-state "$RESULT_DIR/sampling-state.json" \
  --k6-summary "$K6_SUMMARY_FILE" \
  --k6-exit-code "$K6_EXIT_CODE" \
  --k6-container-completed "$K6_CONTAINER_COMPLETED" \
  --polling "$ENABLE_POLLING" \
  --users "$USERS" \
  --db-p99-gate-seconds "$DB_P99_GATE_SECONDS" \
  --db-max-gate-seconds "$DB_MAX_GATE_SECONDS" \
  --done-count-30 "$DONE_COUNT_30" \
  --done-count-60 "$DONE_COUNT_60" \
  --retry-count "$RETRY_COUNT" \
  --fault-required "$({ [ "$FAULT_INJECTION" = "node_crash" ] || [ "$OBSERVATION_MODE" = "hold" ]; } && echo true || echo false)" \
  --fault-injection-valid "$FAULT_INJECTION_VALID" \
  --fault-recovered-inflight "$FAULT_RECOVERED_INFLIGHT" \
  --diagnostic-enabled "$TIMEOUT_DIAGNOSTIC" \
  --diagnostic-analysis "$RESULT_DIR/diagnostic-analysis.json" \
  --takeover-required "$([ "$OBSERVATION_MODE" = "hold" ] && echo true || echo false)" \
  --takeover-evidence "$RESULT_DIR/takeover-evidence.json" \
  --suite-exit-code NOT_OBTAINED \
  --output "$GATE_RESULT_FILE"; then
  GATE_EVAL_CODE=0
else
  GATE_EVAL_CODE=$?
fi

if { [ "$FAULT_INJECTION" = "node_crash" ] || [ "$OBSERVATION_MODE" = "hold" ]; } \
  && [ "$FAULT_INJECTION_VALID" != "true" ]; then
  SUITE_EXIT_CODE=4
else
  SUITE_EXIT_CODE="$GATE_EVAL_CODE"
fi
if [ -f "$GATE_RESULT_FILE" ]; then
  GATE_RESULT_TMP="$GATE_RESULT_FILE.tmp"
  jq --argjson code "$SUITE_EXIT_CODE" '.suiteExitCode = $code' \
    "$GATE_RESULT_FILE" > "$GATE_RESULT_TMP" && mv -f "$GATE_RESULT_TMP" "$GATE_RESULT_FILE" || true
  echo "overall=$(jq -r '.overall // "ERROR"' "$GATE_RESULT_FILE" 2>/dev/null || echo ERROR)"
  echo "suite_exit_code=$SUITE_EXIT_CODE"
else
  echo "overall=ERROR"
  echo "suite_exit_code=$SUITE_EXIT_CODE"
fi
echo "================================================================"
if [ "$SUITE_EXIT_CODE" -eq 0 ]; then
  echo "[GATE CHECK PASSED] 压测场景 $SCENARIO 通过，证据目录: $RESULT_DIR"
elif [ "$SUITE_EXIT_CODE" -eq 4 ]; then
  echo "[GATE CHECK NOT_APPLICABLE] 指定故障注入条件未满足，证据目录: $RESULT_DIR"
else
  echo "[GATE CHECK FAILED] 压测场景 $SCENARIO 未通过，证据目录: $RESULT_DIR"
fi
exit "$SUITE_EXIT_CODE"
