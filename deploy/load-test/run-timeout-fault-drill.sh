#!/usr/bin/env bash
set -Eeuo pipefail

# 依赖故障驱动脚本：在隔离环境后台运行标准套件轮，并在数据库截止时间附近的
# 指定窗口注入单一故障（redis|mysql|rabbitmq|xxljob），记录注入与恢复时间线。
# 闸门仍由 run-timeout-suite.sh 按 45/60 口径判定；本脚本只负责故障时序与注入后核对。
# 用法: run-timeout-fault-drill.sh <redis|mysql|rabbitmq|xxljob> <场景名> <副本数> <工作线程> <maxconn>
# 环境变量: EXAM_ID(必填) USERS DUE_SECONDS(默认120) BUILD_RUNTIME_IMAGE(默认false) PAUSE_*_OFFSET_* 等

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"
COMPOSE_PROJECT="exam-platform-cloud-timeout"
source "$SCRIPT_DIR/process-lifecycle.sh"
umask 077
COMPOSE_BASE=(docker compose --env-file "$ENV_FILE" -p "$COMPOSE_PROJECT"
  -f "$PROJECT_ROOT/docker-compose.yml" -f "$SCRIPT_DIR/compose.timeout-test.yaml")

FAULT_TYPE="${1:?用法: run-timeout-fault-drill.sh <redis|mysql|rabbitmq|xxljob> <场景名> <副本数> <工作线程> <maxconn>}"
SCENARIO="${2:?缺少场景名}"
REPLICAS="${3:-4}"
WORKERS="${4:-8}"
MYSQL_MAX_CONN="${5:-300}"
USERS="${USERS:-10000}"
EXAM_ID="${EXAM_ID:?EXAM_ID is required}"
DUE_SECONDS="${DUE_SECONDS:-120}"

mkdir -p "$SCRIPT_DIR/results"

RESULT_DIR=""
SUITE_PID=""
SUITE_EXIT="NOT_OBTAINED"
SUITE_WAITED=false
FAULT_INJECTED=false
FAULT_RECOVERED=false
DRILL_ERROR=0
CLEANUP_DONE=false

case "$FAULT_TYPE" in
  redis)    PAUSE_OFFSET="-15"; RESUME_OFFSET="15" ;;
  mysql)    PAUSE_OFFSET="-10"; RESUME_OFFSET="10" ;;
  rabbitmq) PAUSE_OFFSET="5";   RESUME_OFFSET="35" ;;
  xxljob)   PAUSE_OFFSET="5";   RESUME_OFFSET="10" ;;
  *) echo "未知故障类型: $FAULT_TYPE" >&2; exit 2 ;;
esac

DRILL_LOG="$SCRIPT_DIR/results/drill-$(date +%Y%m%d_%H%M%S)-$SCENARIO.log"
exec > >(tee "$DRILL_LOG") 2>&1

mysql_exec() { "${COMPOSE_BASE[@]}" exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N "$@"' _ "$@"; }

find_result_dir() {
  local candidate
  while IFS= read -r candidate; do
    if [ -f "$candidate/metadata.json" ] \
      && grep -Fq "\"examId\": $EXAM_ID" "$candidate/metadata.json"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <(
    find "$SCRIPT_DIR/results" -mindepth 1 -maxdepth 1 -type d \
      -name "*-$SCENARIO" -printf '%T@ %p\n' 2>/dev/null \
      | sort -nr | cut -d' ' -f2-
  )
  return 1
}

set_drill_error() {
  DRILL_ERROR=2
  echo "[工具错误] $*" >&2
}

append_timeline() {
  local message="$(date --iso-8601=seconds) $*"
  if [ -n "$RESULT_DIR" ] && [ -d "$RESULT_DIR" ]; then
    printf '%s\n' "$message" >> "$RESULT_DIR/fault-timeline.txt"
  else
    set_drill_error "结果目录尚未确认，无法封存时间线事件: $*"
  fi
  echo "$message"
}

wait_suite() {
  local wait_code=0
  if [ "$SUITE_WAITED" = true ]; then
    [ "$SUITE_EXIT" = "0" ]
    return $?
  fi
  if [ -z "$SUITE_PID" ]; then
    SUITE_EXIT="NOT_OBTAINED"
    SUITE_WAITED=true
    return 2
  fi
  if wait_process "$SUITE_PID"; then
    wait_code=0
  else
    wait_code=$?
  fi
  SUITE_EXIT="$wait_code"
  SUITE_WAITED=true
  # 只有 wait 完成后才清空 PID，避免清理流程把未获取的退出码当作成功。
  SUITE_PID=""
  return "$wait_code"
}

wait_xxl_job_ready() {
  local ready=false
  for _ in $(seq 1 90); do
    if curl -fsS --max-time 2 -o /dev/null http://localhost:18080/xxl-job-admin/ 2>/dev/null; then
      ready=true
      break
    fi
    sleep 1
  done
  [ "$ready" = true ]
}

restore_fault() {
  [ "$FAULT_INJECTED" = true ] || return 0
  local restore_ok=true
  case "$FAULT_TYPE" in
    redis)
      if "${COMPOSE_BASE[@]}" unpause redis; then
        append_timeline "action=redis_unpaused"
      else
        restore_ok=false
      fi
      ;;
    mysql)
      if "${COMPOSE_BASE[@]}" unpause mysql; then
        append_timeline "action=mysql_unpaused"
      else
        restore_ok=false
      fi
      ;;
    rabbitmq)
      if "${COMPOSE_BASE[@]}" unpause rabbitmq; then
        append_timeline "action=rabbitmq_unpaused"
      else
        restore_ok=false
      fi
      ;;
    xxljob)
      if "${COMPOSE_BASE[@]}" start xxl-job-admin; then
        append_timeline "action=xxljob_admin_started"
      else
        restore_ok=false
      fi
      if [ "$restore_ok" = true ] && wait_xxl_job_ready; then
        append_timeline "action=xxljob_admin_http_ready"
      else
        restore_ok=false
        append_timeline "action=xxljob_admin_http_ready_failed"
      fi
      ;;
  esac
  if [ "$restore_ok" = true ]; then
    FAULT_RECOVERED=true
    FAULT_INJECTED=false
    return 0
  fi
  set_drill_error "$FAULT_TYPE 故障恢复失败，保留现场并阻止本轮通过"
  return 1
}

cleanup() {
  [ "$CLEANUP_DONE" = true ] && return 0
  CLEANUP_DONE=true

  # 任何异常路径都先恢复单一故障，再停止仍在运行的内层套件。
  if [ "$FAULT_INJECTED" = true ] && ! restore_fault; then
    DRILL_ERROR=2
  fi
  if [ -n "$SUITE_PID" ]; then
    local suite_stop_code=0
    if stop_process_group "$SUITE_PID" 8; then
      suite_stop_code=0
    else
      suite_stop_code=$?
    fi
    SUITE_PID=""
    if [ "$suite_stop_code" -ne 0 ] && [ "$suite_stop_code" -ne 130 ] \
      && [ "$suite_stop_code" -ne 143 ]; then
      set_drill_error "无法停止内层套件进程组，退出码=$suite_stop_code"
    fi
  fi
}

on_exit() {
  local exit_code=$?
  trap - EXIT INT TERM
  cleanup
  if [ "$exit_code" -eq 0 ] && [ "$DRILL_ERROR" -ne 0 ]; then
    exit_code=2
  fi
  exit "$exit_code"
}

trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "================================================================"
echo "  依赖故障演练: type=$FAULT_TYPE scenario=$SCENARIO replicas=$REPLICAS workers=$WORKERS"
echo "  EXAM_ID=$EXAM_ID USERS=$USERS DUE=+${DUE_SECONDS}s"
echo "  注入窗口: due_at${PAUSE_OFFSET}s 暂停, due_at+${RESUME_OFFSET}s 恢复"
echo "================================================================"

# 1. 启动标准套件轮（后台），闸门由套件判定
export EXAM_ID USERS DUE_SECONDS BUILD_RUNTIME_IMAGE="${BUILD_RUNTIME_IMAGE:-false}"
export DB_P99_GATE_SECONDS="${DB_P99_GATE_SECONDS:-45}" DB_MAX_GATE_SECONDS="${DB_MAX_GATE_SECONDS:-60}"
SUITE_LOG="/tmp/timeout-suite-$SCENARIO-$$.log"
SUITE_ERR_LOG="/tmp/timeout-suite-$SCENARIO-$$.err.log"
echo "[$(date +'%T')] 后台启动标准套件轮（日志: $SUITE_LOG）..."
start_process_group SUITE_PID "$SUITE_LOG" "$SUITE_ERR_LOG" \
  env JAVA_HOME="${JAVA_HOME:-}" EXAM_ID="$EXAM_ID" USERS="$USERS" \
  DUE_SECONDS="$DUE_SECONDS" BUILD_RUNTIME_IMAGE="$BUILD_RUNTIME_IMAGE" \
  DB_P99_GATE_SECONDS="$DB_P99_GATE_SECONDS" DB_MAX_GATE_SECONDS="$DB_MAX_GATE_SECONDS" \
  bash "$SCRIPT_DIR/run-timeout-suite.sh" \
  "$SCENARIO" "$REPLICAS" "$WORKERS" "$MYSQL_MAX_CONN"

# 2. 等待套件完成数据准备（检测本轮任务的 due_at）
echo "[$(date +'%T')] 等待数据准备完成..."
DEADLINE_SEC=""
for _ in $(seq 1 240); do
  if ! process_group_exists "$SUITE_PID"; then
    echo "[FATAL] 套件在数据准备前退出" >&2
    tail -20 "$SUITE_LOG" 2>/dev/null || true
    wait_suite || true
    exit 2
  fi
  CANDIDATE_RESULT_DIR=$(find_result_dir || true)
  [ -n "$CANDIDATE_RESULT_DIR" ] && RESULT_DIR="$CANDIDATE_RESULT_DIR"
  DUE_RAW=$(mysql_exec exam_runtime -e \
    "select floor(unix_timestamp(min(due_at))) from submission_timeout_task
      where exam_id=$EXAM_ID and due_at < now() + interval 10 minute;" 2>/dev/null | head -n 1 || true)
  case "$DUE_RAW" in ''|*[!0-9]*) DUE_RAW="" ;; esac
  if [ -n "$DUE_RAW" ] && [ -n "$RESULT_DIR" ]; then DEADLINE_SEC="$DUE_RAW"; break; fi
  sleep 2
done
if [ -z "$DEADLINE_SEC" ] || [ -z "$RESULT_DIR" ] || [ ! -d "$RESULT_DIR" ]; then
  set_drill_error "未能同时获取本轮截止时间和有效结果目录"
  wait_suite || true
  exit 2
fi
echo "[$(date +'%T')] 截止时间 unix=$DEADLINE_SEC (距今 $((DEADLINE_SEC - $(date +%s)))s)"

# 4. 按窗口注入故障
PAUSE_AT=$((DEADLINE_SEC + PAUSE_OFFSET))
NOW=$(date +%s)
if [ "$NOW" -lt "$PAUSE_AT" ]; then
  echo "[$(date +'%T')] 等待注入窗口 ($((PAUSE_AT - NOW))s)..."
  sleep "$((PAUSE_AT - NOW))"
fi
append_timeline "suite_result_dir=$RESULT_DIR"
inject_timeline() { append_timeline "$@"; }

if [ "$FAULT_TYPE" = "redis" ]; then
  inject_timeline "action=redis_pause started"
  FAULT_INJECTED=true
  if "${COMPOSE_BASE[@]}" pause redis; then
    inject_timeline "action=redis_paused"
  else
    set_drill_error "Redis 故障注入失败"
    exit 2
  fi
elif [ "$FAULT_TYPE" = "mysql" ]; then
  inject_timeline "action=mysql_pause started"
  FAULT_INJECTED=true
  if "${COMPOSE_BASE[@]}" pause mysql; then
    inject_timeline "action=mysql_paused"
  else
    set_drill_error "MySQL 故障注入失败"
    exit 2
  fi
elif [ "$FAULT_TYPE" = "rabbitmq" ]; then
  inject_timeline "action=rabbitmq_pause started"
  FAULT_INJECTED=true
  if "${COMPOSE_BASE[@]}" pause rabbitmq; then
    inject_timeline "action=rabbitmq_paused"
  else
    set_drill_error "RabbitMQ 故障注入失败"
    exit 2
  fi
elif [ "$FAULT_TYPE" = "xxljob" ]; then
  inject_timeline "action=xxljob_kill started"
  FAULT_INJECTED=true
  if "${COMPOSE_BASE[@]}" kill xxl-job-admin; then
    inject_timeline "action=xxljob_admin_killed"
  else
    set_drill_error "XXL-Job 故障注入失败"
    exit 2
  fi
  sleep 5
  PENDING_AT_5S=$(mysql_exec exam_runtime -e "select status, count(*) from submission_timeout_task where exam_id=$EXAM_ID group by status;" | tr '\n' ' ')
  inject_timeline "action=xxljob_status_5s_after_kill: $PENDING_AT_5S"
  echo "kill 后 5s 任务状态: $PENDING_AT_5S"
fi

RESUME_AT=$((DEADLINE_SEC + RESUME_OFFSET))
NOW=$(date +%s)
if [ "$NOW" -lt "$RESUME_AT" ]; then
  sleep "$((RESUME_AT - NOW))"
fi

if [ "$FAULT_TYPE" = "redis" ]; then
  restore_fault || { wait_suite || true; exit 2; }
elif [ "$FAULT_TYPE" = "mysql" ]; then
  restore_fault || { wait_suite || true; exit 2; }
elif [ "$FAULT_TYPE" = "rabbitmq" ]; then
  restore_fault || { wait_suite || true; exit 2; }
elif [ "$FAULT_TYPE" = "xxljob" ]; then
  restore_fault || { wait_suite || true; exit 2; }
fi

# 5. 等待套件结束
echo "[$(date +'%T')] 等待套件轮结束..."
wait_suite || true
if [ -z "$RESULT_DIR" ] || [ ! -d "$RESULT_DIR" ]; then
  set_drill_error "套件结束后仍无法确认结果目录"
  exit 2
fi
append_timeline "suite_exit_code=$SUITE_EXIT"
echo "套件退出码: $SUITE_EXIT, 结果目录: $RESULT_DIR"

# 6. 注入后业务收敛核对
echo "[$(date +'%T')] 执行注入后业务收敛核对..."
{
  echo "--- 注入后任务终态 ---"
  mysql_exec exam_runtime -e "select status, count(*) from submission_timeout_task where exam_id=$EXAM_ID group by status;"
  echo "--- Outbox 状态分布（当前考试）---"
  mysql_exec exam_runtime -e "select oe.status, count(*) from outbox_event oe join submission s on oe.aggregate_id=cast(s.id as char) where s.exam_id=$EXAM_ID group by oe.status;"
  echo "--- 尝试次数分布 ---"
  mysql_exec exam_runtime -e "select attempt_count, count(*) from submission_timeout_task where exam_id=$EXAM_ID group by attempt_count;"
} > "$RESULT_DIR/post-fault-convergence.txt" 2>&1
cat "$RESULT_DIR/post-fault-convergence.txt"

if [ "$FAULT_TYPE" = "rabbitmq" ]; then
  echo "[$(date +'%T')] 恢复后持续采样 Outbox 分布至终态（最长 300s）..."
  DRAIN_STARTED=$(date +%s)
  : > "$RESULT_DIR/outbox-drain-timeline.tsv"
  for _ in $(seq 1 150); do
    DIST=$(mysql_exec exam_runtime -e \
      "select concat(coalesce(sum(oe.status='PENDING'),0),'/',coalesce(sum(oe.status='SENDING'),0),'/',coalesce(sum(oe.status='PUBLISHED'),0),'/',coalesce(sum(oe.status='FAILED'),0)) from outbox_event oe join submission s on oe.aggregate_id=cast(s.id as char) where s.exam_id=$EXAM_ID;")
    echo -e "$(date --iso-8601=seconds)\tpending/sending/published/failed=$DIST" >> "$RESULT_DIR/outbox-drain-timeline.tsv"
    if [ "$DIST" = "0/0/$USERS/0" ]; then
      echo "outbox_drained_after_seconds=$(( $(date +%s) - DRAIN_STARTED ))" | tee -a "$RESULT_DIR/fault-timeline.txt"
      break
    fi
    sleep 2
  done
  mysql_exec exam_runtime -e "select oe.status, count(*) from outbox_event oe join submission s on oe.aggregate_id=cast(s.id as char) where s.exam_id=$EXAM_ID group by oe.status;" \
    | tee -a "$RESULT_DIR/fault-timeline.txt"
fi

if [ "$FAULT_TYPE" = "xxljob" ]; then
  echo "[$(date +'%T')] 核对 XXL-Job 配置与恢复后的触发记录..."
  {
    echo "--- job 配置（应保持 SHARDING_BROADCAST/SERIAL_EXECUTION/DO_NOTHING）---"
    mysql_exec xxl_job -e "select job_desc, schedule_conf, executor_route_strategy, misfire_strategy, trigger_status from xxl_job_info where executor_handler='examTimeoutSubmitJob';"
    echo "--- 恢复后触发记录（最近 10 条）---"
    mysql_exec xxl_job -e "select trigger_time, trigger_code, trigger_msg from xxl_job_log order by trigger_time desc limit 10;"
  } > "$RESULT_DIR/xxljob-verification.txt" 2>&1
  cat "$RESULT_DIR/xxljob-verification.txt"
fi

if [ "$SUITE_EXIT" = "NOT_OBTAINED" ]; then
  set_drill_error "未获取内层套件真实退出码"
  exit 2
fi
if [ "$DRILL_ERROR" -ne 0 ]; then
  exit 2
fi
exit "$SUITE_EXIT"
