import http from 'k6/http'
import exec from 'k6/execution'
import { SharedArray } from 'k6/data'
import { check, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'

const tokenFile = __ENV.TOKENS_FILE || './tokens.json'
const tokens = new SharedArray('timeout-submission-tokens', () => JSON.parse(open(tokenFile)))
const examId = __ENV.EXAM_ID
const deadlineEpochMs = Number(__ENV.DEADLINE_EPOCH_MS)
const baseUrl = __ENV.BASE_URL || 'http://localhost:16730/api/v1'
const users = Number(__ENV.USERS || 10000)
const pollBaseMs = Number(__ENV.POLL_BASE_MS || 10000)
const pollMaxMs = Number(__ENV.POLL_MAX_MS || 10000)
const loadFlow = __ENV.LOAD_FLOW || 'status_only'
const dbP99GateMs = Number(__ENV.DB_P99_GATE_MS || 30000)
const dbMaxGateMs = Number(__ENV.DB_MAX_GATE_MS || 60000)
const diagnosticEnabled = __ENV.TIMEOUT_DIAGNOSTIC === 'true'
const diagnosticStride = Number(__ENV.DIAGNOSTIC_SAMPLE_STRIDE || 10)
const sessionMapFile = __ENV.SESSION_MAP_FILE
const sessionMap = diagnosticEnabled
  ? new SharedArray('timeout-diagnostic-session-map', () => {
    if (!sessionMapFile) throw new Error('SESSION_MAP_FILE is required when diagnostics are enabled')
    const value = JSON.parse(open(sessionMapFile))
    if (!Array.isArray(value?.entries)) throw new Error('SESSION_MAP_FILE entries are required')
    return value.entries
  })
  : null

if (!examId || !Number.isFinite(deadlineEpochMs) || deadlineEpochMs <= 0) {
  throw new Error('EXAM_ID and DEADLINE_EPOCH_MS are required')
}
if (!Array.isArray(tokens) || tokens.length < users) {
  throw new Error(`TOKENS_FILE must contain at least ${users} access tokens`)
}
if (!Number.isFinite(pollBaseMs) || pollBaseMs <= 0
  || !Number.isFinite(pollMaxMs) || pollMaxMs < pollBaseMs) {
  throw new Error('POLL_BASE_MS must be positive and POLL_MAX_MS must be >= POLL_BASE_MS')
}
if (!['status_only', 'deadline_submit', 'deadline_status_first'].includes(loadFlow)) {
  throw new Error('LOAD_FLOW must be status_only, deadline_submit or deadline_status_first')
}
if (!Number.isFinite(dbP99GateMs) || dbP99GateMs <= 0
  || !Number.isFinite(dbMaxGateMs) || dbMaxGateMs <= dbP99GateMs) {
  throw new Error('DB gate milliseconds must be positive and max must be greater than P99')
}
if (diagnosticEnabled && (!Number.isSafeInteger(diagnosticStride) || diagnosticStride < 1)) {
  throw new Error('DIAGNOSTIC_SAMPLE_STRIDE must be a positive integer')
}

const completionLatency = new Trend('timeout_submission_completion_latency', true)
const incomplete = new Counter('timeout_submission_incomplete')
const handoffAccepted = new Counter('timeout_submission_handoff_accepted')
const handoffUncertain = new Counter('timeout_submission_handoff_uncertain')
const diagnosticRequestDuration = new Trend('timeout_diagnostic_request_duration', true)
const diagnosticRequestMarker = new Counter('timeout_diagnostic_request_marker')
const diagnosticPollWait = new Trend('timeout_diagnostic_poll_wait', true)
const diagnosticSessionCompletion = new Trend('timeout_diagnostic_session_completion', true)
const waitBeforeDeadlineSeconds = Math.max(
  0,
  Math.ceil((deadlineEpochMs - Date.now()) / 1000)
)
const scenarioMaxDuration = `${Math.max(90, waitBeforeDeadlineSeconds + 90)}s`

export const options = {
  scenarios: {
    timeout_completion: {
      executor: 'shared-iterations',
      vus: users,
      iterations: users,
      maxDuration: scenarioMaxDuration
    }
  },
  thresholds: {
    timeout_submission_completion_latency: [`p(99)<${dbP99GateMs}`, `max<${dbMaxGateMs}`],
    timeout_submission_incomplete: ['count==0'],
    iterations: [`count==${users}`],
    checks: ['rate==1']
  }
}

export default function () {
  const index = exec.scenario.iterationInTest
  const token = tokens[index]
  const diagnosticEntry = diagnosticEnabled ? sessionMap[index] : null
  const diagnose = diagnosticEnabled && index % diagnosticStride === 0
  if (diagnosticEnabled && (!diagnosticEntry || String(diagnosticEntry.iteration) !== String(index))) {
    throw new Error(`SESSION_MAP_FILE has no verified mapping for iteration ${index}`)
  }
  const diagnosticSessionId = diagnose ? String(diagnosticEntry.sessionId) : null
  const diagnosticRequests = []
  const diagnosticWaits = []
  const headers = { Authorization: `Bearer ${token}` }
  let completionObservedAtMs = null
  while (Date.now() < deadlineEpochMs) {
    const waitStarted = Date.now()
    sleep(Math.min(0.5, Math.max(0.01, (deadlineEpochMs - Date.now()) / 1000)))
    recordDiagnosticWait(
      diagnosticWaits, diagnosticSessionId, index, 'before-deadline', waitStarted, Date.now()
    )
  }

  const stopAt = deadlineEpochMs + dbMaxGateMs
  let submitted = false
  let handoffObserved = false
  let attempt = 0
  if (loadFlow === 'deadline_status_first') {
    attempt += 1
    const waitStarted = Date.now()
    sleep(Math.min(
      submissionPollDelaySeconds(attempt),
      Math.max(0.01, (stopAt - Date.now()) / 1000)
    ))
    recordDiagnosticWait(
      diagnosticWaits, diagnosticSessionId, index, 'poll', waitStarted, Date.now()
    )
    if (Date.now() <= stopAt) {
      const requestStarted = Date.now()
      const response = http.get(`${baseUrl}/exams/${examId}/submission-status`, {
        headers,
        tags: { name: 'deadline-status-probe' }
      })
      const requestEnded = Date.now()
      let body = null
      if (response.status === 200) {
        body = response.json('data')
        if (isRuntimeFinalized(body)) {
          completionLatency.add(Date.now() - deadlineEpochMs)
          completionObservedAtMs = completionObservedAtMs || Date.now()
          submitted = true
        } else if (isHandoffAccepted(body)) {
          handoffAccepted.add(1)
          handoffObserved = true
        }
      }
      recordDiagnosticRequest(
        diagnosticRequests, diagnosticSessionId, index, 'deadline-status-probe',
        response.status, body, requestStarted, requestEnded
      )
    }
  }
  if (!submitted && (loadFlow === 'deadline_submit'
    || loadFlow === 'deadline_status_first' && !handoffObserved)) {
    const requestStarted = Date.now()
    const response = http.post(
      `${baseUrl}/exams/${examId}/submit`,
      JSON.stringify({ answers: [] }),
      {
        headers: { ...headers, 'Content-Type': 'application/json' },
        timeout: '10s',
        tags: { name: 'deadline-submit' }
      }
    )
    const requestEnded = Date.now()
    let body = null
    if (response.status === 200) {
      body = response.json('data')
      if (isRuntimeFinalized(body)) {
        completionLatency.add(Date.now() - deadlineEpochMs)
        completionObservedAtMs = completionObservedAtMs || Date.now()
        submitted = true
      } else if (isHandoffAccepted(body)) {
        handoffAccepted.add(1)
      } else {
        handoffUncertain.add(1)
      }
    } else {
      handoffUncertain.add(1)
    }
    recordDiagnosticRequest(
      diagnosticRequests, diagnosticSessionId, index, 'deadline-submit',
      response.status, body, requestStarted, requestEnded
    )
  }
  while (!submitted && Date.now() <= stopAt) {
    attempt += 1
    const waitStarted = Date.now()
    sleep(Math.min(
      submissionPollDelaySeconds(attempt),
      Math.max(0.01, (stopAt - Date.now()) / 1000)
    ))
    recordDiagnosticWait(
      diagnosticWaits, diagnosticSessionId, index, 'poll', waitStarted, Date.now()
    )
    if (Date.now() > stopAt) {
      break
    }
    const requestStarted = Date.now()
    const response = http.get(`${baseUrl}/exams/${examId}/submission-status`, {
      headers,
      tags: { name: 'submission-status' }
    })
    const requestEnded = Date.now()
    let body = null
    if (response.status === 200) {
      body = response.json('data')
      if (isRuntimeFinalized(body)) {
        completionLatency.add(Date.now() - deadlineEpochMs)
        completionObservedAtMs = completionObservedAtMs || Date.now()
        submitted = true
        recordDiagnosticRequest(
          diagnosticRequests, diagnosticSessionId, index, 'submission-status',
          response.status, body, requestStarted, requestEnded
        )
        break
      }
    }
    recordDiagnosticRequest(
      diagnosticRequests, diagnosticSessionId, index, 'submission-status',
      response.status, body, requestStarted, requestEnded
    )
  }

  if (!submitted) {
    incomplete.add(1)
  }
  check(submitted, { 'session completed within 60 seconds': (value) => value === true })
  flushDiagnostics(
    diagnose, diagnosticSessionId, index, diagnosticRequests, diagnosticWaits,
    submitted, completionObservedAtMs
  )
}

function recordDiagnosticRequest(events, sessionId, iteration, requestKind,
                                 statusCode, body, startedAtMs, endedAtMs) {
  if (!sessionId) return
  events.push({
    sessionId,
    iteration: String(iteration),
    requestKind,
    statusCode: String(statusCode),
    phase: String(body?.phase || 'UNKNOWN'),
    startedAtMs,
    endedAtMs,
    durationMs: Math.max(0, endedAtMs - startedAtMs)
  })
}

function recordDiagnosticWait(events, sessionId, iteration, waitKind, startedAtMs, endedAtMs) {
  if (!sessionId) return
  events.push({
    sessionId,
    iteration: String(iteration),
    waitKind,
    startedAtMs,
    endedAtMs,
    durationMs: Math.max(0, endedAtMs - startedAtMs)
  })
}

function flushDiagnostics(diagnose, sessionId, iteration, requests, waits, submitted, completedAtMs) {
  if (!diagnose || !sessionId) return
  // The session timeline stays in the VU heap until the session ends. Only
  // then are custom samples emitted to k6's JSON output, so diagnostic output
  // does not synchronously write one line per request.
  for (const event of requests) {
    const tags = {
      session_id: event.sessionId,
      iteration: event.iteration,
      request_kind: event.requestKind,
      status_code: event.statusCode,
      phase: event.phase,
      started_epoch_ms: String(event.startedAtMs),
      ended_epoch_ms: String(event.endedAtMs)
    }
    diagnosticRequestDuration.add(event.durationMs, tags)
    diagnosticRequestMarker.add(1, tags)
  }
  for (const event of waits) {
    diagnosticPollWait.add(event.durationMs, {
      session_id: event.sessionId,
      iteration: event.iteration,
      wait_kind: event.waitKind,
      started_epoch_ms: String(event.startedAtMs),
      ended_epoch_ms: String(event.endedAtMs)
    })
  }
  diagnosticSessionCompletion.add(submitted ? 1 : 0, {
    session_id: sessionId,
    iteration: String(iteration),
    completed: String(submitted),
    completed_epoch_ms: completedAtMs == null ? 'null' : String(completedAtMs),
    conclusion: submitted ? 'RUNTIME_FINALIZED_OBSERVED' : 'NOT_COMPLETED_BY_CLIENT_DEADLINE'
  })
}

function submissionPollDelaySeconds(attempt) {
  const safeAttempt = Math.max(1, Number(attempt) || 1)
  const baseDelayMs = Math.min(pollMaxMs, pollBaseMs * (1.5 ** Math.min(safeAttempt - 1, 4)))
  return (baseDelayMs * (0.8 + Math.random() * 0.4)) / 1000
}

function isRuntimeFinalized(body) {
  return body?.runtimeFinalized === true
    || body?.phase === 'RUNTIME_FINALIZED'
    || body?.sessionStatus === 'SUBMITTED'
      && ['PROCESSING', 'SUBMITTED'].includes(body?.submissionStatus)
}

function isHandoffAccepted(body) {
  return isRuntimeFinalized(body)
    || body?.phase === 'HANDOFF_ACCEPTED'
    || body?.status === 'SUBMITTING'
    || body?.sessionStatus === 'AUTO_SUBMITTING'
}
