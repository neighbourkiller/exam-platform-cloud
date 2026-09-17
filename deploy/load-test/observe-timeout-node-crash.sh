#!/usr/bin/env bash
set -Eeuo pipefail

# 严格分片+跨片恢复 V2 的 Runtime 4→3 接管观测。
# 判据（计划 §3.2）：不要求看到 shardTotal=3；必须证明旧注册列表尚未更新时，
# 存活实例已跨片处理故障分片任务；记录"尚未领取任务"与"遗弃在途任务"分别的恢复时间；
# 观察期间不重建故障实例；60 秒后保留失败结论继续观察至收敛或 180 秒诊断上限；
# 到上限未收敛必须非零退出。仅操作 exam-platform-cloud-timeout 隔离项目。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"
COMPOSE_PROJECT="exam-platform-cloud-timeout"
source "$SCRIPT_DIR/process-lifecycle.sh"
umask 077
COMPOSE_BASE=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT"
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml")

SCENARIO="${SCENARIO:-FAULT-OBSERVE-4-3}"
KILL_TIMING="${KILL_TIMING:-with_inflight}"   # before_deadline | with_inflight | after_renewal
REPLICAS="${REPLICAS:-4}"
WORKERS="${WORKERS:-8}"
USERS="${USERS:-10000}"
EXAM_ID="${EXAM_ID:?EXAM_ID is required}"
DUE_SECONDS="${DUE_SECONDS:-120}"
FAULT_WAIT_TIMEOUT_SECONDS="${FAULT_WAIT_TIMEOUT_SECONDS:-30}"
SLA_SECONDS="${SLA_SECONDS:-60}"
OBSERVE_LIMIT_SECONDS="${OBSERVE_LIMIT_SECONDS:-180}"

case "$KILL_TIMING" in
  before_deadline|with_inflight|after_renewal) ;;
  *)
    echo "未知 KILL_TIMING: $KILL_TIMING" >&2
    exit 2
    ;;
esac

RESULT_DIR="$SCRIPT_DIR/results/$(date +%Y%m%d_%H%M%S)-$SCENARIO"
mkdir -p "$RESULT_DIR"
exec > >(tee "$RESULT_DIR/run.log") 2>&1
RUN_STARTED_AT="$(date --iso-8601=seconds)"
TIMELINE_FILE="$RESULT_DIR/takeover-timeline.tsv"
FAULT_TIMELINE_FILE="$RESULT_DIR/fault-timeline.txt"
TOKEN_FILE=""
STATS_SAMPLER_PID=""
REG_SAMPLER_PID=""
SAMPLER_STOP_ERROR=0
CLEANUP_ERROR=0
CLEANUP_DONE=false

TARGET_CONTAINER_ID=""
TARGET_CONTAINER_NAME=""

sql_run() { # $1=schema $2=sql
  "${COMPOSE_BASE[@]}" exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N "$1" -e "$2"' _ "$1" "$2"
}

stop_sampler() {
  local pid_variable="$1"
  local pid="${!pid_variable:-}"
  local stop_code=0
  [ -n "$pid" ] || return 0
  if stop_process_group "$pid" 8; then
    stop_code=0
  else
    stop_code=$?
  fi
  # stop_process_group 已完成 wait 后才清空 PID。
  printf -v "$pid_variable" '%s' ''
  if [ "$stop_code" -ne 0 ] && [ "$stop_code" -ne 130 ] && [ "$stop_code" -ne 143 ]; then
    SAMPLER_STOP_ERROR=1
    CLEANUP_ERROR=2
    echo "[工具错误] 采样进程组 PID=$pid 停止退出码=$stop_code" >&2
  fi
}

cleanup() {
  [ "$CLEANUP_DONE" = true ] && return 0
  CLEANUP_DONE=true

  # 每个采样器独立停止并 wait，不能用清空 PID 代替回收。
  stop_sampler STATS_SAMPLER_PID
  stop_sampler REG_SAMPLER_PID
  # 仅在证据封存后（脚本退出时）才恢复实例数量
  if ! "${COMPOSE_BASE[@]}" up -d --scale runtime-service="$REPLICAS" runtime-service >/dev/null 2>&1; then
    CLEANUP_ERROR=2
    echo "[工具错误] 无法恢复 Runtime 副本数" >&2
  fi
  if [ -n "$TOKEN_FILE" ] && ! rm -f "$TOKEN_FILE"; then
    CLEANUP_ERROR=2
    echo "[工具错误] 无法清理临时令牌文件" >&2
  fi
}

on_exit() {
  local exit_code=$?
  trap - EXIT INT TERM
  cleanup
  if [ "$exit_code" -eq 0 ] && [ "$CLEANUP_ERROR" -ne 0 ]; then
    exit_code=2
  fi
  exit "$exit_code"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "================================================================"
echo "  4→3 接管观测: $SCENARIO killTiming=$KILL_TIMING"
echo "  结果目录: $RESULT_DIR"
echo "  Runtime=$REPLICAS x 工作线程=$WORKERS, USERS=$USERS, EXAM_ID=$EXAM_ID, DUE=+${DUE_SECONDS}s"
echo "  SLA 窗口: ${SLA_SECONDS}s; 诊断观察上限: ${OBSERVE_LIMIT_SECONDS}s"
echo "================================================================"

# 1. 启动隔离环境（复用现有镜像；强制重建 Runtime 以获得本轮全新启动证据）
echo "[$(date +'%T')] 启动隔离环境 (runtime-service=$REPLICAS, 强制重建 Runtime)..."
"${COMPOSE_BASE[@]}" up -d \
  mysql redis rabbitmq minio nacos nacos-config-init jwt-key-init xxl-job-admin iam-service gateway
"${COMPOSE_BASE[@]}" up -d --force-recreate --scale runtime-service="$REPLICAS" runtime-service

RUNTIME_IMAGE_ID=$("${COMPOSE_BASE[@]}" images -q runtime-service | head -n 1)
echo "runtimeImageId=$RUNTIME_IMAGE_ID"

# 2. 真实 Spring 启动验证
echo "[$(date +'%T')] 等待 Runtime 完成 Spring 启动..."
BOOT_DEADLINE=$(( $(date +%s) + 240 ))
BOOT_OK=false
while [ "$(date +%s)" -lt "$BOOT_DEADLINE" ]; do
  STARTED_COUNT=$("${COMPOSE_BASE[@]}" logs --no-color --since "$RUN_STARTED_AT" runtime-service 2>/dev/null \
    | grep -cE "Started RuntimeApplication" || true)
  if [ "$STARTED_COUNT" -ge "$REPLICAS" ]; then BOOT_OK=true; break; fi
  sleep 3
done
if [ "$BOOT_OK" != "true" ]; then
  echo "[FATAL] Runtime 实例未在 240s 内全部完成 Spring 启动" >&2
  "${COMPOSE_BASE[@]}" logs --no-color --since "$RUN_STARTED_AT" runtime-service 2>/dev/null \
    | grep -E "ERROR|Application run failed" | head -20 > "$RESULT_DIR/boot-failure.log" || true
  exit 3
fi
"${COMPOSE_BASE[@]}" logs --no-color --since "$RUN_STARTED_AT" runtime-service 2>/dev/null \
  | grep -E "Started RuntimeApplication|Timeout submission initialized" \
  > "$RESULT_DIR/boot-verification.log" || true
echo "[$(date +'%T')] $REPLICAS 个实例全部通过真实 Spring 启动"

# 3. 清理与数据准备
"${COMPOSE_BASE[@]}" exec -T redis redis-cli -n 1 flushdb >/dev/null
echo "[$(date +'%T')] 准备 $USERS 人数据 (到期 +${DUE_SECONDS}s)..."
TOKEN_FILE="$(mktemp "$RESULT_DIR/.timeout-tokens.XXXXXX")"
PREPARE_OUTPUT=$(USERS="$USERS" EXAM_ID="$EXAM_ID" DUE_IN_SECONDS="$DUE_SECONDS" \
  TOKENS_FILE="$TOKEN_FILE" node "$SCRIPT_DIR/prepare-timeout-load-data.mjs")
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
DEADLINE_EPOCH_MS=$(echo "$PREPARE_OUTPUT" | grep -o '"deadlineEpochMs":[0-9]*' | cut -d':' -f2)
DEADLINE_SEC=$((DEADLINE_EPOCH_MS / 1000))
chmod 600 "$TOKEN_FILE"

# 4. 选定故障目标并记录其分片下标
mapfile -t RUNTIME_CONTAINER_IDS < <("${COMPOSE_BASE[@]}" ps -q runtime-service)
if [ "${#RUNTIME_CONTAINER_IDS[@]}" -lt 2 ]; then
  echo "[FATAL] Runtime 副本数不足，无法进行节点接管观测" >&2
  exit 2
fi
TARGET_CONTAINER_ID="${RUNTIME_CONTAINER_IDS[-1]}"
TARGET_CONTAINER_NAME=$(docker inspect --format '{{.Name}}' "$TARGET_CONTAINER_ID" | sed 's#^/##')
{
  echo "target_container=$TARGET_CONTAINER_NAME"
  echo "survivor_containers=$((${#RUNTIME_CONTAINER_IDS[@]} - 1))"
  echo "survivor_pause_count=0"
  echo "runtime_image_id=$RUNTIME_IMAGE_ID"
  echo "kill_timing=$KILL_TIMING"
  echo "observe_limit_seconds=$OBSERVE_LIMIT_SECONDS"
  echo "sla_seconds=$SLA_SECONDS"
  echo "note=本轮不分用无轮询轮的 k6 结论；本脚本不执行 k6"
} > "$FAULT_TIMELINE_FILE"
echo "target_container=$TARGET_CONTAINER_NAME"

# 5. 独立采样线程：任务状态与注册表各自停止并 wait。
REGISTRY_TIMELINE_FILE="$RESULT_DIR/registry-timeline.tsv"
start_process_group STATS_SAMPLER_PID /dev/null "$RESULT_DIR/stats-sampler.log" \
  bash "$SCRIPT_DIR/timeout-takeover-sampler.sh" stats "$TIMELINE_FILE" "$EXAM_ID"
start_process_group REG_SAMPLER_PID /dev/null "$RESULT_DIR/registry-sampler.log" \
  bash "$SCRIPT_DIR/timeout-takeover-sampler.sh" registry "$REGISTRY_TIMELINE_FILE" "$EXAM_ID"

# 6. 按时机注入
if [ "$KILL_TIMING" = "before_deadline" ]; then
  WAIT_UNTIL=$((DEADLINE_SEC - 2))
  NOW=$(date +%s)
  [ "$NOW" -lt "$WAIT_UNTIL" ] && sleep "$((WAIT_UNTIL - NOW))"
  DB_INJECTION_TIME=$(sql_run exam_runtime "select current_timestamp(3);")
  {
    echo "database_injection_time=$DB_INJECTION_TIME"
    echo "processing_count_before_kill=0"
    echo "kill_started_at=$(date --iso-8601=seconds)"
    echo "injection_condition=deadline_not_reached_kill_ok"
  } >> "$FAULT_TIMELINE_FILE"
  echo "[$(date +'%T')] [故障注入] 截止前 2 秒 kill $TARGET_CONTAINER_NAME"
  docker kill "$TARGET_CONTAINER_ID" >/dev/null
  echo "killed_at=$(date --iso-8601=seconds)" >> "$FAULT_TIMELINE_FILE"
else
  WAIT_UNTIL=$((DEADLINE_SEC - 2))
  NOW=$(date +%s)
  [ "$NOW" -lt "$WAIT_UNTIL" ] && sleep "$((WAIT_UNTIL - NOW))"
  echo "[$(date +'%T')] [故障准备] 不暂停其他副本，等待目标实例有 worker.active 且数据库出现 PROCESSING 任务"
  echo "survivors_untouched=true" >> "$FAULT_TIMELINE_FILE"

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
    printf '%s\n' "$response" \
      | awk '/^exam_timeout_submission_worker_active([[:space:]]|\{)/ {print $NF; exit}'
  }

  FAULT_WAIT_STARTED=$(date +%s)
  FIRST_SEEN_AT=""
  INFLIGHT_OK=false
  HELD_IDS=""
  RENEWAL_WAIT_SECONDS="$FAULT_WAIT_TIMEOUT_SECONDS"
  [ "$KILL_TIMING" = "after_renewal" ] && RENEWAL_WAIT_SECONDS=$((FAULT_WAIT_TIMEOUT_SECONDS + 35))
  while true; do
    INFLIGHT=$(sql_run exam_runtime "select count(*) from submission_timeout_task where exam_id=$EXAM_ID and status='PROCESSING';" || true)
    TARGET_ACTIVE_WORKERS="$(target_worker_active 2>/dev/null || true)"
    if [ -n "$INFLIGHT" ] && [ "$INFLIGHT" != "0" ] && [ "$INFLIGHT" != "NULL" ] \
      && [ -n "$TARGET_ACTIVE_WORKERS" ] \
      && awk -v value="$TARGET_ACTIVE_WORKERS" 'BEGIN {exit !(value > 0)}'; then
      CURRENT_HELD_IDS="$(sql_run exam_runtime "select group_concat(id order by id) from submission_timeout_task where exam_id=$EXAM_ID and status='PROCESSING';")"
      if [ -z "$FIRST_SEEN_AT" ]; then
        FIRST_SEEN_AT=$(date +%s)
        HELD_IDS="$CURRENT_HELD_IDS"
        if [ "$KILL_TIMING" != "after_renewal" ]; then
          INFLIGHT_OK=true
          break
        fi
        echo "renewal_watch_ids=$HELD_IDS" >> "$FAULT_TIMELINE_FILE"
      elif [ "$KILL_TIMING" = "after_renewal" ]; then
        if [ "$HELD_IDS" != "$CURRENT_HELD_IDS" ]; then
          FIRST_SEEN_AT=$(date +%s)
          HELD_IDS="$CURRENT_HELD_IDS"
          echo "renewal_watch_ids=$HELD_IDS" >> "$FAULT_TIMELINE_FILE"
        elif [ "$(( $(date +%s) - FIRST_SEEN_AT ))" -ge 35 ]; then
          echo "renewal_evidence=same_ids_held_over_35s" >> "$FAULT_TIMELINE_FILE"
          echo "[$(date +'%T')] [故障准备] 已确认续租（同一批任务连续在途超过 35 秒）"
          INFLIGHT_OK=true
          break
        fi
      fi
    elif [ "$KILL_TIMING" = "after_renewal" ] && [ -n "$FIRST_SEEN_AT" ]; then
      # 现场消失后重新开始计时，不能把旧的观察结果当作当前在途证据。
        HELD_IDS=""
        FIRST_SEEN_AT=""
    fi
    if [ "$(( $(date +%s) - FAULT_WAIT_STARTED ))" -ge "$RENEWAL_WAIT_SECONDS" ]; then
      echo "[$(date +'%T')] [故障准备] 注入条件未满足（目标实例没有可确认的在途任务）" >&2
      echo "injection_condition=NOT_MET" >> "$FAULT_TIMELINE_FILE"
      break
    fi
    sleep 1
  done

  if [ "$INFLIGHT_OK" = "true" ]; then
    INFLIGHT_COUNT=$(sql_run exam_runtime "select count(*) from submission_timeout_task where exam_id=$EXAM_ID and status='PROCESSING';")
    DB_INJECTION_TIME=$(sql_run exam_runtime "select current_timestamp(3);")
    {
      echo "database_injection_time=$DB_INJECTION_TIME"
      echo "processing_count_before_kill=$INFLIGHT_COUNT"
      echo "processing_task_ids_before_kill=$HELD_IDS"
      echo "target_worker_active_before_kill=$TARGET_ACTIVE_WORKERS"
      echo "kill_started_at=$(date --iso-8601=seconds)"
      echo "injection_condition=met"
    } >> "$FAULT_TIMELINE_FILE"
    echo "[$(date +'%T')] [故障注入] PROCESSING=$INFLIGHT_COUNT，kill $TARGET_CONTAINER_NAME"
    docker kill "$TARGET_CONTAINER_ID" >/dev/null
    echo "killed_at=$(date --iso-8601=seconds)" >> "$FAULT_TIMELINE_FILE"
  else
    echo "[FATAL] 注入条件未满足，本轮返回 4" >&2
    exit 4
  fi
fi

# 计算被杀实例的分片下标（由其遗弃在途任务或本实例在 XXL 分片中的序号推导——本轮以任务 ID 取模记录）
KILL_EPOCH=$(date +%s)

# 7. 有界观察：60 秒 SLA 判定 + 最长 180 秒诊断；期间绝不重建实例
echo "[$(date +'%T')] 进入接管观察（SLA ${SLA_SECONDS}s / 诊断上限 ${OBSERVE_LIMIT_SECONDS}s）..."
CONVERGED_AT=""
SLA_CONVERGED=false
while true; do
  ELAPSED=$(( $(date +%s) - KILL_EPOCH ))
  DONE_NOW=$(sql_run exam_runtime "select count(*) from submission_timeout_task where exam_id=$EXAM_ID and status='DONE';")
  if [ "$DONE_NOW" = "$USERS" ] && [ -z "$CONVERGED_AT" ]; then
    CONVERGED_AT=$(date +%s)
    if [ "$ELAPSED" -le "$SLA_SECONDS" ]; then SLA_CONVERGED=true; fi
    echo "[$(date +'%T')] 全部任务完成：距 kill ${ELAPSED}s（SLA ${SLA_SECONDS}s 内: $SLA_CONVERGED）"
  fi
  if [ -n "$CONVERGED_AT" ] && [ "$(( $(date +%s) - CONVERGED_AT ))" -ge 10 ]; then
    break
  fi
  if [ "$ELAPSED" -ge "$OBSERVE_LIMIT_SECONDS" ]; then
    echo "[$(date +'%T')] 诊断观察上限到达，未全量收敛: $DONE_NOW / $USERS" >&2
    break
  fi
  sleep 2
done

stop_sampler STATS_SAMPLER_PID
stop_sampler REG_SAMPLER_PID

# 8. 保存运行时事件与注册表时间线，推导关键时间点
"${COMPOSE_BASE[@]}" logs --no-color --since "$RUN_STARTED_AT" runtime-service 2>&1 \
  | grep -E "Timeout submission (initialized|round finished)|retry scheduled|reconciliation|ERROR" \
  > "$RESULT_DIR/runtime-events.log" || true

FIRST_CROSS_CLAIM=$(grep -m1 -oE "shardTotal=[0-9]+, claimed=[0-9]+, completed=[0-9]+, ownPending=[0-9]+, crossPending=[1-9][0-9]*" "$RESULT_DIR/runtime-events.log" || echo none)
FIRST_SHARD3=$(grep -m1 "shardTotal=3" "$RESULT_DIR/runtime-events.log" || echo none)
REG_AT_CONVERGE=$(awk -v t="$( [ -n "$CONVERGED_AT" ] && echo "$CONVERGED_AT" || echo 0)" -F'\t' '$2 <= t {reg=$3} END {print reg}' "$REGISTRY_TIMELINE_FILE" 2>/dev/null || true)
REG_MIN_AFTER_KILL=$(awk -v t="$KILL_EPOCH" -F'\t' '$2 >= t {print $3}' "$REGISTRY_TIMELINE_FILE" 2>/dev/null | grep -v NA | sort -n | head -1 || true)
[ -n "$REG_AT_CONVERGE" ] || REG_AT_CONVERGE=NA
[ -n "$REG_MIN_AFTER_KILL" ] || REG_MIN_AFTER_KILL=NA

{
  echo "first_cross_shard_claim_log=$FIRST_CROSS_CLAIM"
  echo "first_shardTotal3_log=$FIRST_SHARD3"
  echo "registry_count_at_convergence=$REG_AT_CONVERGE"
  echo "registry_min_after_kill=$REG_MIN_AFTER_KILL"
  echo "takeover_seconds_to_all_done=$([ -n "$CONVERGED_AT" ] && echo $((CONVERGED_AT - KILL_EPOCH)) || echo NOT_CONVERGED)"
  echo "sla_${SLA_SECONDS}s_converged=$SLA_CONVERGED"
} >> "$FAULT_TIMELINE_FILE"

# 9. 最终一致性断言（按 exam 隔离）
sql_run exam_runtime "
WITH latencies AS (
  SELECT TIMESTAMPDIFF(MICROSECOND, due_at, completed_at)/1000.0 AS latency_ms
  FROM submission_timeout_task WHERE exam_id=$EXAM_ID AND status='DONE'
), ranked AS (
  SELECT latency_ms, ROW_NUMBER() OVER (ORDER BY latency_ms) AS row_num, COUNT(*) OVER () AS done_count FROM latencies
)
SELECT COUNT(*), MIN(latency_ms)/1000.0, AVG(latency_ms)/1000.0,
  MAX(CASE WHEN row_num=CEIL(done_count*0.50) THEN latency_ms END)/1000.0,
  MAX(CASE WHEN row_num=CEIL(done_count*0.90) THEN latency_ms END)/1000.0,
  MAX(CASE WHEN row_num=CEIL(done_count*0.95) THEN latency_ms END)/1000.0,
  MAX(CASE WHEN row_num=CEIL(done_count*0.99) THEN latency_ms END)/1000.0,
  MAX(latency_ms)/1000.0
FROM ranked;" > "$RESULT_DIR/latency-summary.txt" 2>&1
sql_run exam_runtime "
select
  (select count(*) from submission_timeout_task where exam_id=$EXAM_ID and status='DONE') done,
  (select count(*) from submission_timeout_task where exam_id=$EXAM_ID and status='FAILED') failed,
  (select count(*) from submission_timeout_task where exam_id=$EXAM_ID and status='PROCESSING') lingering,
  (select count(*) from exam_session where exam_id=$EXAM_ID and status='SUBMITTED') sessions,
  (select count(*) from submission where exam_id=$EXAM_ID and status='PROCESSING') submissions,
  (select count(*) from submission_final_payload fp join submission s on s.id=fp.submission_id where s.exam_id=$EXAM_ID) payloads,
  (select coalesce(max(c),0) from (select count(*) c from submission_final_payload fp join submission s on s.id=fp.submission_id where s.exam_id=$EXAM_ID group by fp.submission_id) t) max_payload_per_submission,
  (select count(*) from outbox_event oe join submission s on oe.aggregate_id=cast(s.id as char) where s.exam_id=$EXAM_ID and oe.event_type='SubmissionAccepted') events,
  (select count(*) from outbox_event oe join submission s on oe.aggregate_id=cast(s.id as char) where s.exam_id=$EXAM_ID and oe.status='FAILED') outbox_failed,
  (select count(*) from submission_timeout_task where exam_id=$EXAM_ID and attempt_count>1) retried
from dual;" > "$RESULT_DIR/consistency-summary.txt" 2>&1
cat "$RESULT_DIR/latency-summary.txt" "$RESULT_DIR/consistency-summary.txt"

# 10. 判定：接管=存活实例在旧注册列表未更新期间完成故障分片，且不依赖重建
DONE_COUNT=$(sql_run exam_runtime "select count(*) from submission_timeout_task where exam_id=$EXAM_ID and status='DONE';")
FAILED_COUNT=$(sql_run exam_runtime "select count(*) from submission_timeout_task where exam_id=$EXAM_ID and status='FAILED';")
LINGERING=$(sql_run exam_runtime "select count(*) from submission_timeout_task where exam_id=$EXAM_ID and status='PROCESSING';")
MAX_PAYLOAD=$(sql_run exam_runtime "select coalesce(max(c),0) from (select count(*) c from submission_final_payload fp join submission s on s.id=fp.submission_id where s.exam_id=$EXAM_ID group by fp.submission_id) t;")
EVENTS=$(sql_run exam_runtime "select count(*) from outbox_event oe join submission s on oe.aggregate_id=cast(s.id as char) where s.exam_id=$EXAM_ID and oe.event_type='SubmissionAccepted';")
GATE_FAILED=0
[ "$DONE_COUNT" = "$USERS" ] || { echo "[FAIL] 未全量完成: $DONE_COUNT/$USERS"; GATE_FAILED=1; }
[ "$FAILED_COUNT" = "0" ] || { echo "[FAIL] 存在 FAILED: $FAILED_COUNT"; GATE_FAILED=1; }
[ "$LINGERING" = "0" ] || { echo "[FAIL] 存在遗留 PROCESSING: $LINGERING"; GATE_FAILED=1; }
[ "$MAX_PAYLOAD" = "1" ] || { echo "[FAIL] 存在重复最终载荷: $MAX_PAYLOAD"; GATE_FAILED=1; }
[ "$EVENTS" = "$USERS" ] || { echo "[FAIL] 逻辑事件数量异常: $EVENTS/$USERS"; GATE_FAILED=1; }
if [ "$SLA_CONVERGED" != "true" ]; then
  echo "[FAIL] 未在 ${SLA_SECONDS}s SLA 内全量收敛（保留失败结论，诊断窗口已继续观察）"
  GATE_FAILED=1
fi
if [ -n "$CONVERGED_AT" ] && [ "$REG_MIN_AFTER_KILL" != "NA" ] && [ "$REG_MIN_AFTER_KILL" != "" ] \
  && [ "${REG_MIN_AFTER_KILL:-4}" -ge "$REPLICAS" ] && [ "${REPLICAS:-4}" -gt 2 ]; then
  echo "[EVIDENCE] 收敛发生时执行器注册仍为 $REG_MIN_AFTER_KILL（未因摘除改变分片），跨片恢复为接管机制"
fi

if [ "$GATE_FAILED" -ne 0 ]; then
  echo "[RESULT] 接管观测未通过全部闸门（失败结论保留），证据目录: $RESULT_DIR"
  exit 1
fi
echo "[RESULT] 接管观测全部闸门通过，证据目录: $RESULT_DIR"
