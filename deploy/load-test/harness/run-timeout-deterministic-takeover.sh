#!/usr/bin/env bash
set -Eeuo pipefail

# 按计划执行四轮确定性接管机制测试：
#   CLAIM_HELD 两轮，然后 RENEW_SUCCEEDED 两轮。
# 每一轮均使用独立考试 ID、200 个真实任务、隔离观察组件和当前 Runtime
# 镜像。标准套件的 SLA 结果原样保留；机制是否可继续由接管、终态和采样
# 证据单独判断，不能把一次 SLA 失败误当成没有发生接管。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOAD_TEST_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$LOAD_TEST_ROOT/../.." && pwd)"
SUITE_SCRIPT="$SCRIPT_DIR/run-timeout-suite.sh"
RESULT_ROOT="$LOAD_TEST_ROOT/results"
umask 077

USERS="${USERS:-200}"
REPLICAS="${REPLICAS:-4}"
WORKERS="${WORKERS:-8}"
MYSQL_MAX_CONN="${MYSQL_MAX_CONN:-300}"
DUE_SECONDS="${DUE_SECONDS:-60}"
DB_P99_GATE_SECONDS="${DB_P99_GATE_SECONDS:-45}"
DB_MAX_GATE_SECONDS="${DB_MAX_GATE_SECONDS:-60}"
OBSERVATION_WAIT_TIMEOUT_SECONDS="${OBSERVATION_WAIT_TIMEOUT_SECONDS:-30}"
OBSERVATION_LIMIT_SECONDS="${OBSERVATION_LIMIT_SECONDS:-180}"
BUILD_RUNTIME_IMAGE="${BUILD_RUNTIME_IMAGE:-true}"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  cat <<'USAGE'
用法：run-timeout-deterministic-takeover.sh

默认严格执行四轮机制测试：
  1. 领取后杀（CLAIM_HELD）
  2. 领取后杀复测（CLAIM_HELD）
  3. 续租后杀（RENEW_SUCCEEDED）
  4. 续租后杀复测（RENEW_SUCCEEDED）

可用环境变量：USERS（默认 200）、REPLICAS（4）、WORKERS（8）、
MYSQL_MAX_CONN（300）、DUE_SECONDS（60）、ENV_FILE、
TAKEOVER_EXAM_BASE_ID（不指定时按时间生成唯一基准 ID）、
BUILD_RUNTIME_IMAGE（默认 true）。

本脚本不会自动暂停存活实例，也不会在证据封存前重建被杀实例。
USAGE
  exit 0
fi

positive_integer() {
  local name="$1"
  local value="$2"
  if ! [[ "$value" =~ ^[1-9][0-9]*$ ]]; then
    echo "$name 必须是正整数: $value" >&2
    exit 2
  fi
}

positive_integer USERS "$USERS"
positive_integer REPLICAS "$REPLICAS"
positive_integer WORKERS "$WORKERS"
positive_integer MYSQL_MAX_CONN "$MYSQL_MAX_CONN"
positive_integer DUE_SECONDS "$DUE_SECONDS"
positive_integer OBSERVATION_WAIT_TIMEOUT_SECONDS "$OBSERVATION_WAIT_TIMEOUT_SECONDS"
positive_integer OBSERVATION_LIMIT_SECONDS "$OBSERVATION_LIMIT_SECONDS"
if [ "$USERS" -ne 200 ]; then
  echo "确定性接管计划要求 USERS=200，当前为 $USERS；如需非计划规模请直接调用单轮套件" >&2
  exit 2
fi
if [ "$REPLICAS" -lt 2 ]; then
  echo "确定性接管至少需要两个 Runtime 实例" >&2
  exit 2
fi
if [ "$BUILD_RUNTIME_IMAGE" != true ] && [ "$BUILD_RUNTIME_IMAGE" != false ]; then
  echo "BUILD_RUNTIME_IMAGE 必须为 true 或 false" >&2
  exit 2
fi
if [ ! -x "$SUITE_SCRIPT" ]; then
  echo "缺少可执行的标准套件: $SUITE_SCRIPT" >&2
  exit 2
fi

timestamp="$(date +%Y%m%d_%H%M%S)"
run_id="deterministic-takeover-$timestamp"
orchestration_dir="$RESULT_ROOT/$run_id"
mkdir -p "$orchestration_dir"
summary_tsv="$orchestration_dir/rounds.tsv"
manifest_json="$orchestration_dir/manifest.json"
console_log="$orchestration_dir/orchestrator.log"
exec > >(tee "$console_log") 2>&1

epoch_now="$(date +%s)"
if [ -n "${TAKEOVER_EXAM_BASE_ID:-}" ]; then
  exam_base="$TAKEOVER_EXAM_BASE_ID"
else
  # exam_id 使用时间戳低位生成，保留四个连续 ID 给本次运行。
  exam_base=$((991000000000 + (epoch_now % 1000000) * 10))
fi
positive_integer TAKEOVER_EXAM_BASE_ID "$exam_base"
if [ "$exam_base" -gt 9223372036854775804 ]; then
  echo "考试 ID 基准过大，无法为四轮保留连续 ID: $exam_base" >&2
  exit 2
fi

cat > "$summary_tsv" <<'HEADER'
round	kill_on	exam_id	suite_exit_code	mechanism_status	sla_status	target_container_state	result_dir
HEADER

cat > "$manifest_json" <<JSON
{
  "runId": "$run_id",
  "startedAt": "$(date --iso-8601=seconds)",
  "users": $USERS,
  "replicas": $REPLICAS,
  "workers": $WORKERS,
  "mysqlMaxConnections": $MYSQL_MAX_CONN,
  "dueSeconds": $DUE_SECONDS,
  "dbP99GateSeconds": $DB_P99_GATE_SECONDS,
  "dbMaxGateSeconds": $DB_MAX_GATE_SECONDS,
  "observationWaitTimeoutSeconds": $OBSERVATION_WAIT_TIMEOUT_SECONDS,
  "observationLimitSeconds": $OBSERVATION_LIMIT_SECONDS,
  "examBaseId": $exam_base,
  "plannedRounds": [
    {"round": "claim-1", "killOn": "CLAIM_HELD", "examId": $((exam_base + 0))},
    {"round": "claim-2", "killOn": "CLAIM_HELD", "examId": $((exam_base + 1))},
    {"round": "renew-1", "killOn": "RENEW_SUCCEEDED", "examId": $((exam_base + 2))},
    {"round": "renew-2", "killOn": "RENEW_SUCCEEDED", "examId": $((exam_base + 3))}
  ]
}
JSON
chmod 600 "$summary_tsv" "$manifest_json"

find_result_dir() {
  local console_file="$1"
  local exam_id="$2"
  local scenario="$3"
  local candidate
  candidate="$(sed -n 's/.*结果保存目录: //p' "$console_file" | tail -n 1 || true)"
  if [ -n "$candidate" ] && [[ "$candidate" == "$RESULT_ROOT/"* ]] \
    && [ -f "$candidate/metadata.json" ] \
    && jq -e --argjson exam "$exam_id" '.examId == $exam' "$candidate/metadata.json" >/dev/null 2>&1; then
    printf '%s\n' "$candidate"
    return 0
  fi
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    if [ -f "$candidate/metadata.json" ] \
      && jq -e --argjson exam "$exam_id" '.examId == $exam' "$candidate/metadata.json" >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <(find "$RESULT_ROOT" -mindepth 1 -maxdepth 1 -type d -name "*-$scenario" -printf '%T@ %p\n' 2>/dev/null | sort -nr | cut -d' ' -f2-)
  return 1
}

database_consistency_ok() {
  local database_summary="$1"
  local expected_users="$2"
  node - "$database_summary" "$expected_users" <<'NODE'
import fs from 'node:fs'

const [file, expectedText] = process.argv.slice(2)
const expected = Number(expectedText)
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).map(line => line.trim()).filter(Boolean)
const headerIndex = lines.findIndex(line => line.startsWith('done_tasks\tsubmitted_sessions\tprocessing_submissions\t'))
if (headerIndex < 0 || !lines[headerIndex + 1]) process.exit(1)
const header = lines[headerIndex].split('\t')
const row = lines[headerIndex + 1].split('\t')
const value = name => Number(row[header.indexOf(name)])
const expectedValues = {
  done_tasks: expected,
  submitted_sessions: expected,
  processing_submissions: expected,
  final_payloads: expected,
  draft_payloads: expected,
  null_submission_ids: 0,
  failed_tasks: 0,
  lingering_processing_tasks: 0,
  outbox_accepted_events: expected,
  outbox_failed_events: 0
}
for (const [name, wanted] of Object.entries(expectedValues)) {
  if (!Number.isFinite(value(name)) || value(name) !== wanted) process.exit(1)
}
process.exit(0)
NODE
}

metadata_matches() {
  local metadata="$1"
  local exam_id="$2"
  jq -e --argjson exam "$exam_id" --argjson users "$USERS" \
    '.examId == $exam and .users == $users and .observationMode == "hold" and .timeoutDiagnostic == false and .faultInjection == "none" and .targetTaskId != null and .loadFlow == "status_only"' \
    "$metadata" >/dev/null 2>&1
}

write_round_summary() {
  local file="$1"
  local round="$2"
  local kill_on="$3"
  local exam_id="$4"
  local suite_code="$5"
  local mechanism_status="$6"
  local sla_status="$7"
  local target_state="$8"
  local result_dir="$9"
  local takeover_status="${10}"
  local sampling_status="${11}"
  local consistency_status="${12}"
  local errors_file="${13}"
  jq -n \
    --arg round "$round" \
    --arg killOn "$kill_on" \
    --argjson examId "$exam_id" \
    --argjson suiteExitCode "$suite_code" \
    --arg mechanismStatus "$mechanism_status" \
    --arg slaStatus "$sla_status" \
    --arg targetContainerState "$target_state" \
    --arg resultDir "$result_dir" \
    --arg takeoverStatus "$takeover_status" \
    --arg samplingStatus "$sampling_status" \
    --arg consistencyStatus "$consistency_status" \
    --arg errorsFile "$(basename "$errors_file")" \
    '{round:$round,killOn:$killOn,examId:$examId,suiteExitCode:$suiteExitCode,mechanismStatus:$mechanismStatus,slaStatus:$slaStatus,targetContainerState:$targetContainerState,resultDir:$resultDir,takeoverStatus:$takeoverStatus,samplingStatus:$samplingStatus,consistencyStatus:$consistencyStatus,errorsFile:$errorsFile}' \
    > "$file"
  chmod 600 "$file"
}

run_round() {
  local round="$1"
  local kill_on="$2"
  local exam_id="$3"
  local build_image="$4"
  local console_file="$orchestration_dir/$round.console.log"
  local suite_code=0
  local result_dir=""
  local validation_file=""
  local takeover_status=ERROR
  local sampling_status=ERROR
  local consistency_status=ERROR
  local target_state=missing
  local mechanism_status=ERROR
  local sla_status=UNKNOWN

  echo "================================================================"
  echo "开始确定性接管机制轮: round=$round kill_on=$kill_on exam_id=$exam_id users=$USERS"
  echo "================================================================"
  set +e
  USERS="$USERS" EXAM_ID="$exam_id" DUE_SECONDS="$DUE_SECONDS" \
    REPLICAS="$REPLICAS" WORKERS="$WORKERS" MYSQL_MAX_CONN="$MYSQL_MAX_CONN" \
    DB_P99_GATE_SECONDS="$DB_P99_GATE_SECONDS" DB_MAX_GATE_SECONDS="$DB_MAX_GATE_SECONDS" \
    OBSERVATION_MODE=hold OBSERVATION_KILL_ON="$kill_on" \
    OBSERVATION_WAIT_TIMEOUT_SECONDS="$OBSERVATION_WAIT_TIMEOUT_SECONDS" \
    OBSERVATION_LIMIT_SECONDS="$OBSERVATION_LIMIT_SECONDS" \
    ENABLE_POLLING=true LOAD_FLOW=status_only BUILD_RUNTIME_IMAGE="$build_image" \
    ENV_FILE="$ENV_FILE" bash "$SUITE_SCRIPT" "$round" "$REPLICAS" "$WORKERS" "$MYSQL_MAX_CONN" \
    2>&1 | tee "$console_file"
  suite_code="${PIPESTATUS[0]}"
  set -e

  result_dir="$(find_result_dir "$console_file" "$exam_id" "$round" || true)"
  validation_file="$orchestration_dir/$round.validation.txt"
  : > "$validation_file"
  if [ -z "$result_dir" ]; then
    echo "结果目录无法从标准套件输出中解析" | tee -a "$validation_file"
  else
    echo "result_dir=$result_dir" | tee -a "$validation_file"
    if [ -f "$result_dir/metadata.json" ] && metadata_matches "$result_dir/metadata.json" "$exam_id"; then
      echo "metadata=PASS" | tee -a "$validation_file"
    else
      echo "metadata=ERROR" | tee -a "$validation_file"
    fi

    takeover_status="$(jq -r '.status // "ERROR"' "$result_dir/takeover-evidence.json" 2>/dev/null || echo ERROR)"
    sampling_status="$(jq -r '.status // "ERROR"' "$result_dir/sampling-state.json" 2>/dev/null || echo ERROR)"
    if database_consistency_ok "$result_dir/database-summary.txt" "$USERS"; then
      consistency_status=PASS
    else
      consistency_status=ERROR
    fi
    echo "takeover=$takeover_status" | tee -a "$validation_file"
    echo "sampling=$sampling_status" | tee -a "$validation_file"
    echo "database_consistency=$consistency_status" | tee -a "$validation_file"

    target_container="$(sed -n 's/^target_container=//p' "$result_dir/fault-timeline.txt" | tail -n 1 || true)"
    if [ -n "$target_container" ]; then
      target_state="$(docker inspect --format '{{.State.Status}}' "$target_container" 2>/dev/null || echo missing)"
    fi
    echo "target_container_state=$target_state" | tee -a "$validation_file"

    gate_overall="$(jq -r '.overall // "UNKNOWN"' "$result_dir/gate-result.json" 2>/dev/null || echo UNKNOWN)"
    database_gate="$(jq -r '.database.status // "UNKNOWN"' "$result_dir/gate-result.json" 2>/dev/null || echo UNKNOWN)"
    client_gate="$(jq -r '.client.status // "UNKNOWN"' "$result_dir/gate-result.json" 2>/dev/null || echo UNKNOWN)"
    if [ "$database_gate" = PASS ] && { [ "$client_gate" = PASS ] || [ "$client_gate" = NOT_RUN ]; }; then
      sla_status=PASS
    else
      sla_status="$gate_overall"
    fi
    echo "sla_status=$sla_status database_gate=$database_gate client_gate=$client_gate suite_exit_code=$suite_code" \
      | tee -a "$validation_file"

    timeline_ok=true
    for required in 'injection_condition=met' 'survivors_untouched=true' 'survivors_paused=false' 'registry_count_at_kill=' 'killed_at='; do
      if ! grep -Fq "$required" "$result_dir/fault-timeline.txt"; then
        echo "缺少时间线字段: $required" | tee -a "$validation_file"
        timeline_ok=false
      fi
    done
    if ! grep -Fq "kill_on=$kill_on" "$result_dir/fault-timeline.txt"; then
      echo "时间线 kill_on 与计划不一致: expected=$kill_on" | tee -a "$validation_file"
      timeline_ok=false
    fi
    if [ "$kill_on" = RENEW_SUCCEEDED ]; then
      initial_lease="$(sed -n 's/^initial_lease_epoch_ms=//p' "$result_dir/fault-timeline.txt" | tail -n 1 || true)"
      renewed_lease="$(sed -n 's/^renewed_lease_epoch_ms=//p' "$result_dir/fault-timeline.txt" | tail -n 1 || true)"
      if ! node - "$initial_lease" "$renewed_lease" <<'NODE'
const [initialText, renewedText] = process.argv.slice(2)
const initial = Number(initialText)
const renewed = Number(renewedText)
if (!Number.isSafeInteger(initial) || !Number.isSafeInteger(renewed) || renewed <= initial) process.exit(1)
NODE
      then
        echo "续租租约没有证明从初始值延长: initial=$initial_lease renewed=$renewed_lease" | tee -a "$validation_file"
        timeline_ok=false
      fi
    fi

    evidence_ok=false
    if jq -e \
      '.status == "PASS" and .target.taskId != null and .target.database.taskStatus == "DONE" and .target.database.attemptCount > 1 and .target.database.finalPayloadCount == 1 and .target.database.logicalEventCount == 1 and .target.database.uniqueFinalPayloadCount == 1 and .target.database.uniqueLogicalEventCount == 1 and .takeover.newClaimEvidence == true and .takeover.newTokenEvidence == true and .takeover.uniqueFinalPayload == true and .takeover.uniqueLogicalEvent == true' \
      "$result_dir/takeover-evidence.json" >/dev/null 2>&1; then
      evidence_ok=true
    fi
    if [ "$takeover_status" = PASS ] && [ "$sampling_status" = PASS ] \
      && [ "$consistency_status" = PASS ] && [ "$target_state" = exited ] \
      && [ "$timeline_ok" = true ] && [ "$evidence_ok" = true ]; then
      mechanism_status=PASS
    else
      mechanism_status=ERROR
      echo "机制证据未通过：需要 takeover/sampling/终态/被杀实例退出/时间线全部满足" \
        | tee -a "$validation_file"
    fi
  fi

  write_round_summary "$orchestration_dir/$round.summary.json" "$round" "$kill_on" "$exam_id" \
    "$suite_code" "$mechanism_status" "$sla_status" "$target_state" "$result_dir" \
    "$takeover_status" "$sampling_status" "$consistency_status" "$validation_file"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$round" "$kill_on" "$exam_id" "$suite_code" "$mechanism_status" "$sla_status" \
    "$target_state" "$result_dir" >> "$summary_tsv"

  if [ "$mechanism_status" != PASS ]; then
    echo "[STOP] $round 机制证据失败，按计划停止扩大规模；不会自动执行后续轮次"
    return 1
  fi
  echo "[PASS] $round 接管机制证据通过；SLA 原始判定=$sla_status（不作为自然负载容量证明）"
  return 0
}

overall_status=PASS
completed_rounds=0
build_for_round="$BUILD_RUNTIME_IMAGE"
rounds=(
  'claim-1 CLAIM_HELD'
  'claim-2 CLAIM_HELD'
  'renew-1 RENEW_SUCCEEDED'
  'renew-2 RENEW_SUCCEEDED'
)

for index in "${!rounds[@]}"; do
  read -r round kill_on <<< "${rounds[$index]}"
  exam_id=$((exam_base + index))
  if ! run_round "$round" "$kill_on" "$exam_id" "$build_for_round"; then
    overall_status=ERROR
    break
  fi
  completed_rounds=$((completed_rounds + 1))
  # 四轮必须使用同一个已核验候选镜像；只在第一轮按需构建。
  build_for_round=false
done

node - "$manifest_json" "$overall_status" "$completed_rounds" "$summary_tsv" <<'NODE'
import fs from 'node:fs'
const [manifestFile, status, completedText, summaryFile] = process.argv.slice(2)
const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'))
manifest.status = status
manifest.completedRounds = Number(completedText)
manifest.summaryFile = summaryFile.split('/').at(-1)
manifest.finishedAt = new Date().toISOString()
fs.writeFileSync(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 })
NODE

echo "================================================================"
echo "确定性接管编排结束: status=$overall_status completed_rounds=$completed_rounds/4"
echo "编排证据目录: $orchestration_dir"
echo "================================================================"
if [ "$overall_status" = PASS ]; then
  exit 0
fi
exit 1
