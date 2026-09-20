#!/usr/bin/env bash

# 管理压测工具创建的独立进程组。
#
# 约定：start_process_group 的第一个参数是要写入 PID 的变量名；第二、三
# 个参数是 stdout/stderr 文件；剩余参数组成待启动的命令。setsid 使 PID
# 同时成为进程组 ID，停止时可以回收该命令派生的 curl/docker 等子进程。

declare -gA PROCESS_LIFECYCLE_WAITED=()
declare -gA PROCESS_LIFECYCLE_EXIT_CODES=()

start_process_group() {
  local pid_variable="$1"
  local stdout_file="$2"
  local stderr_file="$3"
  shift 3

  if [ "$#" -eq 0 ]; then
    echo "start_process_group requires a command" >&2
    return 2
  fi

  setsid "$@" >"$stdout_file" 2>"$stderr_file" &
  printf -v "$pid_variable" '%s' "$!"
}

process_group_exists() {
  local pid="$1"
  [ -n "$pid" ] || return 1
  kill -0 -- "-$pid" >/dev/null 2>&1 || kill -0 "$pid" >/dev/null 2>&1
}

wait_process() {
  local pid="$1"
  [ -n "$pid" ] || return 0
  if [ "${PROCESS_LIFECYCLE_WAITED[$pid]:-false}" = true ]; then
    return "${PROCESS_LIFECYCLE_EXIT_CODES[$pid]:-0}"
  fi
  wait "$pid" 2>/dev/null
  local wait_code=$?
  PROCESS_LIFECYCLE_WAITED[$pid]=true
  PROCESS_LIFECYCLE_EXIT_CODES[$pid]="$wait_code"
  return "$wait_code"
}

stop_process_group() {
  local pid="$1"
  local timeout_seconds="${2:-10}"
  local deadline
  local wait_code

  [ -n "$pid" ] || return 0
  if [ "${PROCESS_LIFECYCLE_WAITED[$pid]:-false}" = true ]; then
    return 0
  fi

  # 即使 leader 已经退出，也先尝试按原始 PGID 发信号；派生进程可能仍在。
  kill -TERM -- "-$pid" >/dev/null 2>&1 || true
  kill -TERM "$pid" >/dev/null 2>&1 || true

  deadline=$((SECONDS + timeout_seconds))
  while process_group_exists "$pid" && [ "$SECONDS" -lt "$deadline" ]; do
    sleep 0.1
  done

  if process_group_exists "$pid"; then
    kill -KILL -- "-$pid" >/dev/null 2>&1 || true
    kill -KILL "$pid" >/dev/null 2>&1 || true
  fi

  # 只有 wait 完成后调用方才可以清空 PID。已经被其他清理路径 wait 的
  # PID 返回 127，此处按幂等清理处理。
  wait "$pid" 2>/dev/null
  wait_code=$?
  PROCESS_LIFECYCLE_WAITED[$pid]=true
  PROCESS_LIFECYCLE_EXIT_CODES[$pid]="$wait_code"
  [ "$wait_code" -eq 127 ] && return 0
  return "$wait_code"
}
