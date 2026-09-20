#!/usr/bin/env bash
set -Eeuo pipefail

# 固定实例集合的隔离采样器。每一条指标记录都带时间、实例、容器和
# 采样请求起止时间；多行 Prometheus 响应不会共享一个空时间戳。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOAD_TEST_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$LOAD_TEST_ROOT/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-exam-platform-cloud-timeout}"
COMPOSE_FILES=(-f "$PROJECT_ROOT/docker-compose.yml" -f "$LOAD_TEST_ROOT/compose/compose.timeout-test.yaml")
MODE="${1:?mode is required: resource|registry|prometheus}"
OUTPUT_FILE="${2:?output file is required}"
STATE_FILE="${3:?state file is required}"
TOKEN_FILE="${4:-}"
EXPECTED_INSTANCES_FILE="${5:-}"

mkdir -p "$(dirname "$OUTPUT_FILE")" "$(dirname "$STATE_FILE")"
umask 077
declare -a EXPECTED_CONTAINER_IDS=()
declare -a EXPECTED_INSTANCE_IDS=()
declare -a EXPECTED_CONTAINER_NAMES=()

compose() {
  docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT" \
    "${COMPOSE_FILES[@]}" "$@"
}

if [ -n "$EXPECTED_INSTANCES_FILE" ] && [ -r "$EXPECTED_INSTANCES_FILE" ]; then
  while IFS=$'\t' read -r container_id instance_id container_name; do
    [ -n "$container_id" ] || continue
    EXPECTED_CONTAINER_IDS+=("$container_id")
    EXPECTED_INSTANCE_IDS+=("$instance_id")
    EXPECTED_CONTAINER_NAMES+=("$container_name")
  done < "$EXPECTED_INSTANCES_FILE"
fi
if [ "${#EXPECTED_CONTAINER_IDS[@]}" -eq 0 ]; then
  mapfile -t discovered_container_ids < <(compose ps -q runtime-service)
  for container_id in "${discovered_container_ids[@]}"; do
    [ -n "$container_id" ] || continue
    EXPECTED_CONTAINER_IDS+=("$container_id")
    EXPECTED_INSTANCE_IDS+=("$(docker inspect --format '{{.Config.Hostname}}' "$container_id")")
    EXPECTED_CONTAINER_NAMES+=("$(docker inspect --format '{{.Name}}' "$container_id" | sed 's#^/##')")
  done
fi
EXPECTED_INSTANCES="${#EXPECTED_CONTAINER_IDS[@]}"

started_at="$(date --iso-8601=seconds)"
ready=false
stopped=false
valid_samples=0
invalid_samples=0
error_count=0
last_error=""
first_valid_at=""
last_valid_at=""
longest_valid_gap_ms=0
has_hikari=false
has_jvm=false
has_backlog=false
active_instances=0
stop_requested=false

json_escape() {
  node -e 'process.stdout.write(JSON.stringify(process.argv[1]).slice(1, -1))' "$1"
}

expected_ids_json() {
  local joined=""
  local index
  for index in "${!EXPECTED_INSTANCE_IDS[@]}"; do
    [ -n "$joined" ] && joined+=","
    joined+="\"$(json_escape "${EXPECTED_INSTANCE_IDS[$index]}")\""
  done
  printf '[%s]' "$joined"
}

write_state() {
  local temporary_file="${STATE_FILE}.tmp.$$"
  local now="$(date --iso-8601=seconds)"
  cat > "$temporary_file" <<STATE
{
  "mode": "${MODE}",
  "started": true,
  "ready": ${ready},
  "stopped": ${stopped},
  "startedAt": "${started_at}",
  "readyAt": "${ready_at:-}",
  "stoppedAt": "${stopped_at:-}",
  "firstValidAt": "${first_valid_at}",
  "lastValidAt": "${last_valid_at}",
  "lastStateWriteAt": "${now}",
  "validSamples": ${valid_samples},
  "invalidSamples": ${invalid_samples},
  "errorCount": ${error_count},
  "lastError": "$(json_escape "$last_error")",
  "expectedInstances": ${EXPECTED_INSTANCES},
  "activeInstances": ${active_instances},
  "expectedInstanceIds": $(expected_ids_json),
  "longestValidGapMs": ${longest_valid_gap_ms},
  "hasHikari": ${has_hikari},
  "hasJvm": ${has_jvm},
  "hasBacklog": ${has_backlog}
}
STATE
  mv -f "$temporary_file" "$STATE_FILE"
}

record_error() { error_count=$((error_count + 1)); last_error="$1"; }

mark_valid() {
  valid_samples=$((valid_samples + 1))
  if [ "$ready" = false ]; then ready=true; ready_at="$(date --iso-8601=seconds)"; fi
  [ -n "$first_valid_at" ] || first_valid_at="$(date --iso-8601=seconds)"
  if [ -n "$last_valid_at" ]; then
    local previous_ms current_ms gap
    previous_ms="$(date -d "$last_valid_at" +%s%3N 2>/dev/null || echo 0)"
    current_ms="$(date +%s%3N)"; gap=$((current_ms - previous_ms))
    [ "$gap" -gt "$longest_valid_gap_ms" ] && longest_valid_gap_ms="$gap"
  fi
  last_valid_at="$(date --iso-8601=seconds)"
}
mark_invalid() { invalid_samples=$((invalid_samples + 1)); }

write_sample() {
  local sample_epoch_ms="$1" instance_id="$2" container_id="$3"
  local request_started_ms="$4" request_ended_ms="$5" request_duration_ms="$6"
  local metric_name="$7" metric_value="$8" status="$9"
  # TSV 是机器校验格式；docker stats 的聚合值本身可能带制表符，必须
  # 在写行前归一化，不能让一个指标拆成多列。
  metric_value="${metric_value//$'\t'/ }"
  metric_value="${metric_value//$'\n'/ }"
  metric_name="${metric_name//$'\t'/ }"
  instance_id="${instance_id//$'\t'/ }"
  container_id="${container_id//$'\t'/ }"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$sample_epoch_ms" "$instance_id" "$container_id" "$request_started_ms" \
    "$request_ended_ms" "$request_duration_ms" "$metric_name" "$metric_value" "$status" >> "$OUTPUT_FILE"
}

sample_resource() {
  local index container_id instance_id container_name started_ms ended_ms response duration
  local success_count=0
  if [ "$EXPECTED_INSTANCES" -eq 0 ]; then record_error '固定实例集合为空'; mark_invalid; write_state; return; fi
  for index in "${!EXPECTED_CONTAINER_IDS[@]}"; do
    container_id="${EXPECTED_CONTAINER_IDS[$index]}"; instance_id="${EXPECTED_INSTANCE_IDS[$index]}"; container_name="${EXPECTED_CONTAINER_NAMES[$index]}"
    started_ms="$(date +%s%3N)"
    response="$(docker stats --no-stream --format '{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}\t{{.PIDs}}' "$container_id" 2>/dev/null || true)"
    ended_ms="$(date +%s%3N)"; duration=$((ended_ms - started_ms))
    if [ -n "$response" ]; then
      write_sample "$ended_ms" "$instance_id" "$container_id" "$started_ms" "$ended_ms" "$duration" resource "$response" OK
      success_count=$((success_count + 1))
    else
      write_sample "$ended_ms" "$instance_id" "$container_id" "$started_ms" "$ended_ms" "$duration" resource ERROR UNREACHABLE
      record_error "docker stats 失败: $container_name"
    fi
  done
  active_instances="$success_count"
  if [ "$success_count" -eq "$EXPECTED_INSTANCES" ]; then mark_valid; else mark_invalid; fi
  write_state
}

sample_registry() {
  local started_ms="$(date +%s%3N)" ended_ms duration registry
  registry="$(compose exec -T mysql sh -c \
    'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N xxl_job -e "select count(*) from xxl_job_registry where registry_key=0x6578616d2d72756e74696d652d6578656375746f72;"' 2>/dev/null || true)"
  ended_ms="$(date +%s%3N)"; duration=$((ended_ms - started_ms)); registry="$(printf '%s' "$registry" | tr -d '[:space:]')"
  if [[ "$registry" =~ ^[0-9]+$ ]]; then
    write_sample "$ended_ms" registry registry "$started_ms" "$ended_ms" "$duration" xxl_job_registry "$registry" OK; mark_valid
  else
    write_sample "$ended_ms" registry registry "$started_ms" "$ended_ms" "$duration" xxl_job_registry ERROR UNREACHABLE
    record_error 'XXL-Job 注册表查询失败'; mark_invalid
  fi
  write_state
}

read_token() {
  node - "$TOKEN_FILE" <<'NODE'
import fs from 'node:fs'
const tokens = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
if (!Array.isArray(tokens) || typeof tokens[0] !== 'string' || tokens[0].length === 0) throw new Error('TOKEN_FILE 没有可用令牌')
process.stdout.write(tokens[0])
NODE
}

sample_prometheus() {
  local token index container_id instance_id container_name ip response
  local started_ms ended_ms duration sample_epoch_ms filtered metric_line metric_name metric_value
  local complete=0
  if [ -z "$TOKEN_FILE" ] || [ ! -r "$TOKEN_FILE" ]; then record_error 'Prometheus 采样缺少受保护的测试令牌文件'; mark_invalid; write_state; return; fi
  if [ -z "${PROM_TOKEN:-}" ]; then
    if ! PROM_TOKEN="$(read_token)"; then record_error '无法从测试令牌文件读取 Prometheus 访问令牌'; mark_invalid; write_state; return; fi
  fi
  if [ "$EXPECTED_INSTANCES" -eq 0 ]; then record_error '固定实例集合为空'; mark_invalid; write_state; return; fi
  active_instances=0
  for index in "${!EXPECTED_CONTAINER_IDS[@]}"; do
    container_id="${EXPECTED_CONTAINER_IDS[$index]}"; instance_id="${EXPECTED_INSTANCE_IDS[$index]}"; container_name="${EXPECTED_CONTAINER_NAMES[$index]}"
    ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container_id" 2>/dev/null || true)"
    started_ms="$(date +%s%3N)"; response=""
    if [ -n "$ip" ]; then response="$(curl -fsS --max-time 3 -H "Authorization: Bearer $PROM_TOKEN" "http://$ip:16735/actuator/prometheus" 2>/dev/null || true)"; fi
    ended_ms="$(date +%s%3N)"; duration=$((ended_ms - started_ms)); sample_epoch_ms="$ended_ms"
    if [ -z "$response" ]; then
      write_sample "$sample_epoch_ms" "$instance_id" "$container_id" "$started_ms" "$ended_ms" "$duration" __request__ ERROR UNREACHABLE
      record_error "Prometheus 请求失败: $container_name"; continue
    fi
    filtered="$(printf '%s\n' "$response" | grep -E '^(hikaricp_connections_(active|idle|pending)|jvm_memory_used_bytes|exam_timeout_submission_(backlog|oldest_overdue))([ {]|$)' || true)"
    if [ -z "$filtered" ]; then
      write_sample "$sample_epoch_ms" "$instance_id" "$container_id" "$started_ms" "$ended_ms" "$duration" __request__ ERROR MISSING_REQUIRED_METRICS
      record_error "Prometheus 没有目标指标: $container_name"; continue
    fi
    local_instance_hikari=false; local_instance_jvm=false; local_instance_backlog=false
    while IFS= read -r metric_line; do
      [ -n "$metric_line" ] || continue; [[ "$metric_line" = \#* ]] && continue
      metric_name="$(printf '%s\n' "$metric_line" | awk '{print $1}' | sed 's/{.*//')"; metric_value="$(printf '%s\n' "$metric_line" | awk '{print $NF}')"
      [ -n "$metric_name" ] || continue
      write_sample "$sample_epoch_ms" "$instance_id" "$container_id" "$started_ms" "$ended_ms" "$duration" "$metric_name" "$metric_value" OK
      case "$metric_name" in
        hikaricp_connections_*) local_instance_hikari=true; has_hikari=true ;;
        jvm_memory_used_bytes) local_instance_jvm=true; has_jvm=true ;;
        exam_timeout_submission_*) local_instance_backlog=true; has_backlog=true ;;
      esac
    done <<< "$filtered"
    if [ "$local_instance_hikari" = true ] && [ "$local_instance_jvm" = true ] && [ "$local_instance_backlog" = true ]; then complete=$((complete + 1)); else record_error "实例指标不完整: $container_name"; fi
  done
  active_instances="$complete"
  if [ "$complete" -eq "$EXPECTED_INSTANCES" ]; then mark_valid; else mark_invalid; fi
  write_state
}

on_signal() { stop_requested=true; }
on_exit() { local exit_code=$?; trap - EXIT; stopped=true; stopped_at="$(date --iso-8601=seconds)"; write_state || true; exit "$exit_code"; }
trap on_signal INT TERM
trap on_exit EXIT

: > "$OUTPUT_FILE"
write_state
while [ "$stop_requested" = false ]; do
  case "$MODE" in
    resource) sample_resource; sleep "${RESOURCE_SAMPLE_INTERVAL_SECONDS:-1}" ;;
    registry) sample_registry; sleep "${REGISTRY_SAMPLE_INTERVAL_SECONDS:-2}" ;;
    prometheus) sample_prometheus; sleep "${PROMETHEUS_SAMPLE_INTERVAL_SECONDS:-5}" ;;
    *) record_error "未知采样模式: $MODE"; write_state; exit 2 ;;
  esac
done
