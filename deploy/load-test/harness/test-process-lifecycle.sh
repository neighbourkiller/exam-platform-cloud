#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/process-lifecycle.sh"

TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

pid=""
start_process_group pid "$TEST_DIR/normal.out" "$TEST_DIR/normal.err" \
  bash -c 'exit 7'
normal_code=0
wait_process "$pid" || normal_code=$?
[ "$normal_code" -eq 7 ]

pid=""
child_pid_file="$TEST_DIR/child.pid"
start_process_group pid "$TEST_DIR/group.out" "$TEST_DIR/group.err" \
  bash -c "sleep 60 & echo \$! > '$child_pid_file'; wait"
for _ in $(seq 1 20); do
  [ -s "$child_pid_file" ] && break
  sleep 0.05
done
[ -s "$child_pid_file" ]
child_pid="$(cat "$child_pid_file")"

stop_code=0
stop_process_group "$pid" 1 || stop_code=$?
[ "$stop_code" -eq 143 ] || [ "$stop_code" -eq 137 ] || [ "$stop_code" -eq 0 ]
if process_group_exists "$pid"; then
  echo "process group remains after stop" >&2
  exit 1
fi
if kill -0 "$child_pid" 2>/dev/null; then
  echo "child process remains after process-group stop" >&2
  exit 1
fi

# 第二次清理必须幂等，且不能因为 wait 已完成而误报。
stop_process_group "$pid" 1

echo "process lifecycle tests passed"
