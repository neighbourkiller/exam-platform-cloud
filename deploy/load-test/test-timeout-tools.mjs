import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  evaluateTimeoutGates,
  parseDatabaseSummary
} from './evaluate-timeout-gates.mjs'
import {
  parseK6Summary,
  readAndParseK6Summary
} from './parse-k6-summary.mjs'
import { validateSampling } from './validate-sampling.mjs'
import { extractDiagnostics } from './extract-timeout-diagnostics.mjs'
import { analyzeDiagnostics } from './analyze-timeout-diagnostics.mjs'
import { verifyTakeover } from './verify-timeout-takeover.mjs'

const fixtureDir = path.join(path.dirname(new URL(import.meta.url).pathname), 'fixtures')

function readFixture(name) {
  return JSON.parse(fs.readFileSync(path.join(fixtureDir, name), 'utf8'))
}

function passingDatabase(users = 4) {
  return {
    p99Seconds: 20,
    maxSeconds: 50,
    doneAt30: users,
    doneAt60: users,
    failedCount: 0,
    processingCount: 0,
    nullSubmissionCount: 0,
    finalPayloadCount: users,
    draftPayloadCount: users,
    submittedSessionCount: users,
    processingSubmissionCount: users,
    outboxAcceptedCount: users,
    outboxFailedCount: 0,
    retryCount: 0
  }
}

function passingObservability() {
  return { status: 'PASS', errors: [], samplers: {} }
}

function evaluate({ k6, k6ExitCode = 0, enablePolling = true, database = passingDatabase() } = {}) {
  return evaluateTimeoutGates({
    metadata: { timestamp: 'fixture-run', scenario: 'FIXTURE', examId: 1 },
    users: 4,
    dbP99GateSeconds: 45,
    dbMaxGateSeconds: 60,
    enablePolling,
    k6,
    k6ExitCode,
    k6ContainerCompleted: true,
    database,
    observability: passingObservability(),
    fault: { required: false }
  })
}

test('k6 summary parser reads p99/max and false threshold means passed', () => {
  const result = parseK6Summary(readFixture('k6-summary-pass.json'), 4)
  assert.equal(result.status, 'OK')
  assert.equal(result.metrics.completionLatency.p99Ms, 30000)
  assert.equal(result.metrics.completionLatency.maxMs, 50000)
  assert.equal(result.thresholds.latencyP99.passed, true)
  assert.equal(result.thresholdFailures, 0)
})

test('missing p99 is an error and never becomes zero', () => {
  const result = parseK6Summary(readFixture('k6-summary-missing-p99.json'), 4)
  assert.equal(result.status, 'ERROR')
  assert.equal(result.metrics.completionLatency.p99Ms, null)
  assert.match(result.errors.join('\n'), /p\(99\)/)
})

test('empty, invalid and absent k6 summaries are errors', () => {
  const empty = readAndParseK6Summary(path.join(fixtureDir, 'k6-summary-empty.json'), 4)
  assert.equal(empty.status, 'ERROR')
  const invalidPath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'timeout-tools-')), 'bad.json')
  fs.writeFileSync(invalidPath, '{not-json')
  const invalid = readAndParseK6Summary(invalidPath, 4)
  assert.equal(invalid.status, 'ERROR')
  const absent = readAndParseK6Summary(`${invalidPath}.missing`, 4)
  assert.equal(absent.status, 'ERROR')
})

test('database pass plus nonzero k6 exit cannot pass the suite', () => {
  const k6 = parseK6Summary(readFixture('k6-summary-pass.json'), 4)
  const result = evaluate({ k6, k6ExitCode: 17 })
  assert.notEqual(result.overall, 'PASS')
  assert.equal(result.client.status, 'ERROR')
})

test('k6 exit zero with failed thresholds is an evidence contradiction', () => {
  const k6 = parseK6Summary(readFixture('k6-summary-threshold-fail.json'), 4)
  const result = evaluate({ k6, k6ExitCode: 0 })
  assert.equal(result.overall, 'ERROR')
  assert.equal(result.client.status, 'ERROR')
  assert.match(result.reasons.join('\n'), /证据矛盾/)
})

test('client incomplete count produces a failure', () => {
  const k6 = parseK6Summary(readFixture('k6-summary-threshold-fail.json'), 4)
  const result = evaluate({ k6, k6ExitCode: 1 })
  assert.equal(result.overall, 'FAIL')
  assert.equal(result.client.status, 'FAIL')
  assert.match(result.client.failures.join('\n'), /未完成数/)
})

test('no-polling scenario is explicitly NOT_RUN for the client', () => {
  const result = evaluate({ enablePolling: false, k6: null, k6ExitCode: null })
  assert.equal(result.overall, 'PASS')
  assert.equal(result.client.status, 'NOT_RUN')
})

test('completion after the 60 second due_at window fails the database gate', () => {
  const database = passingDatabase()
  database.doneAt60 = 3
  const result = evaluate({ enablePolling: false, database })
  assert.equal(result.overall, 'FAIL')
  assert.equal(result.database.status, 'FAIL')
})

test('database summary parser keeps missing p99 as an error', () => {
  const parsed = parseDatabaseSummary([
    'done_tasks\tmin_s\tavg_s\tp50_s\tp90_s\tp95_s\tp99_s\tmax_s',
    '4\t1\t2\t2\t3\t4\t\t5',
    'done_tasks\tsubmitted_sessions\tprocessing_submissions\tfinal_payloads\tdraft_payloads\tnull_submission_ids\tfailed_tasks\tlingering_processing_tasks\toutbox_accepted_events\toutbox_failed_events',
    '4\t4\t4\t4\t4\t0\t0\t0\t4\t0'
  ].join('\n'))
  assert.equal(parsed.result.p99Seconds, null)
  assert.ok(parsed.errors.some(error => /p99_s/.test(error)))
})

test('sampler started without valid data is not complete', () => {
  const stateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'timeout-sampler-'))
  for (const name of ['resource', 'registry', 'prometheus']) {
    fs.writeFileSync(path.join(stateDir, `${name}.samples`), '')
    fs.writeFileSync(path.join(stateDir, `${name}.state.json`), JSON.stringify({
      mode: name,
      started: true,
      ready: false,
      stopped: true,
      validSamples: 0,
      errorCount: 1,
      hasHikari: false,
      hasJvm: false,
      hasBacklog: false
    }))
  }
  const result = validateSampling({ stateDir, noResidualProcesses: true })
  assert.equal(result.status, 'ERROR')
  assert.ok(result.errors.some(error => /validSamples|ready/.test(error)))
})

test('sampling rejects a resource row expanded by an embedded tab', () => {
  const stateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'timeout-sampler-fields-'))
  fs.writeFileSync(path.join(stateDir, 'expected-instances.tsv'), 'container-1\tinstance-1\truntime-1\n')
  fs.writeFileSync(path.join(stateDir, 'resource.samples'),
    '1000\tinstance-1\tcontainer-1\t900\t1000\t100\tresource\t1%\t2GiB\tOK\n')
  fs.writeFileSync(path.join(stateDir, 'registry.samples'), '1000\tregistry\tregistry\t900\t1000\t100\txxl_job_registry\t4\tOK\n')
  fs.writeFileSync(path.join(stateDir, 'prometheus.samples'), [
    '1000\tinstance-1\tcontainer-1\t900\t1000\t100\thikaricp_connections_active\t1\tOK',
    '1000\tinstance-1\tcontainer-1\t900\t1000\t100\tjvm_memory_used_bytes\t1\tOK',
    '1000\tinstance-1\tcontainer-1\t900\t1000\t100\texam_timeout_submission_backlog\t1\tOK'
  ].join('\n') + '\n')
  for (const [name, extra] of Object.entries({
    resource: { hasHikari: false, hasJvm: false, hasBacklog: false },
    registry: { hasHikari: false, hasJvm: false, hasBacklog: false },
    prometheus: { hasHikari: true, hasJvm: true, hasBacklog: true }
  })) {
    fs.writeFileSync(path.join(stateDir, `${name}.state.json`), JSON.stringify({
      mode: name, started: true, ready: true, stopped: true,
      validSamples: 1, invalidSamples: 0, errorCount: 0,
      expectedInstances: name === 'registry' ? 0 : 1,
      activeInstances: name === 'registry' ? 0 : 1,
      expectedInstanceIds: ['instance-1'],
      ...extra
    }))
  }
  const result = validateSampling({ stateDir })
  assert.equal(result.status, 'ERROR')
  assert.ok(result.errors.some(error => /resource.*样本行/.test(error)))
})

test('fault target is exempt only after the kill point, while survivor gaps remain errors', () => {
  const stateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'timeout-sampler-gap-'))
  fs.writeFileSync(path.join(stateDir, 'expected-instances.tsv'), [
    'container-1\tinstance-1\truntime-1',
    'container-2\tinstance-2\truntime-2'
  ].join('\n') + '\n')
  const resourceRows = [
    '1000\tinstance-1\tcontainer-1\t900\t1000\t100\tresource\t1% 2GiB\tOK',
    '20000\tinstance-1\tcontainer-1\t19900\t20000\t100\tresource\t1% 2GiB\tOK',
    '1000\tinstance-2\tcontainer-2\t900\t1000\t100\tresource\t1% 2GiB\tOK',
    '20000\tinstance-2\tcontainer-2\t19900\t20000\t100\tresource\t1% 2GiB\tOK'
  ]
  fs.writeFileSync(path.join(stateDir, 'resource.samples'), resourceRows.join('\n') + '\n')
  fs.writeFileSync(path.join(stateDir, 'registry.samples'), '1000\tregistry\tregistry\t900\t1000\t100\txxl_job_registry\t4\tOK\n')
  fs.writeFileSync(path.join(stateDir, 'prometheus.samples'), [
    '1000\tinstance-1\tcontainer-1\t900\t1000\t100\thikaricp_connections_active\t1\tOK',
    '1000\tinstance-1\tcontainer-1\t900\t1000\t100\tjvm_memory_used_bytes\t1\tOK',
    '1000\tinstance-1\tcontainer-1\t900\t1000\t100\texam_timeout_submission_backlog\t1\tOK',
    '1000\tinstance-2\tcontainer-2\t900\t1000\t100\thikaricp_connections_active\t1\tOK',
    '1000\tinstance-2\tcontainer-2\t900\t1000\t100\tjvm_memory_used_bytes\t1\tOK',
    '1000\tinstance-2\tcontainer-2\t900\t1000\t100\texam_timeout_submission_backlog\t1\tOK'
  ].join('\n') + '\n')
  for (const name of ['resource', 'registry', 'prometheus']) {
    fs.writeFileSync(path.join(stateDir, `${name}.state.json`), JSON.stringify({
      mode: name, started: true, ready: true, stopped: true,
      validSamples: 1, invalidSamples: 0, errorCount: 0,
      hasHikari: name === 'prometheus', hasJvm: name === 'prometheus', hasBacklog: name === 'prometheus',
      expectedInstances: name === 'registry' ? 0 : 2,
      activeInstances: name === 'registry' ? 0 : 2,
      expectedInstanceIds: ['instance-1', 'instance-2']
    }))
  }
  const result = validateSampling({
    stateDir,
    faultTarget: true,
    faultTargetInstance: 'instance-1',
    faultKilledAtMs: 1000
  })
  assert.equal(result.status, 'ERROR')
  assert.ok(result.errors.some(error => /instance-2/.test(error)))
  assert.ok(!result.errors.some(error => /instance-1/.test(error)))
})

test('diagnostic extraction and per-session analysis preserve stage boundaries', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'timeout-diagnostics-'))
  const sessionMap = path.join(directory, 'session-map.json')
  const clientFile = path.join(directory, 'client.ndjson')
  const extractedFile = path.join(directory, 'client.json')
  const databaseFile = path.join(directory, 'database.tsv')
  const eventsFile = path.join(directory, 'events.ndjson')
  fs.writeFileSync(sessionMap, JSON.stringify({ entries: [
    { iteration: 0, sessionId: '10', studentId: '20', taskId: '30' }
  ] }))
  const tags = { session_id: '10', iteration: '0' }
  fs.writeFileSync(clientFile, [
    JSON.stringify({ metric: 'timeout_diagnostic_request_duration', data: { value: 20, tags: { ...tags, request_kind: 'submission-status', status_code: '200', phase: 'SUBMITTING', started_epoch_ms: '1100', ended_epoch_ms: '1120' } } }),
    JSON.stringify({ metric: 'timeout_diagnostic_poll_wait', data: { value: 500, tags: { ...tags, wait_kind: 'poll', started_epoch_ms: '1000', ended_epoch_ms: '1500' } } }),
    JSON.stringify({ metric: 'timeout_diagnostic_session_completion', data: { value: 1, tags: { ...tags, completed: 'true', completed_epoch_ms: '1600', conclusion: 'RUNTIME_FINALIZED_OBSERVED' } } })
  ].join('\n') + '\n')
  const extracted = extractDiagnostics({ inputPath: clientFile, sessionMapPath: sessionMap, stride: 1 })
  assert.equal(extracted.status, 'OK')
  assert.equal(extracted.sessions['10'].requests.length, 1)
  fs.writeFileSync(extractedFile, JSON.stringify(extracted))
  fs.writeFileSync(databaseFile, [
    'session_id\ttask_id\tdue_at_epoch_ms\tcompleted_at_epoch_ms\tattempt_count\ttask_status\tsession_status\tsubmission_id',
    '10\t30\t1000\t1500\t1\tDONE\tSUBMITTED\t40'
  ].join('\n') + '\n')
  fs.writeFileSync(eventsFile, JSON.stringify({
    eventType: 'STATUS_LOOKUP', sessionId: 10, statusSource: 'INFLIGHT_CACHE',
    runtimeFinalized: false, wallClockEpochMs: 1200, elapsedNanos: 100
  }) + '\n')
  const analyzed = analyzeDiagnostics({
    client: extractedFile,
    databasePath: databaseFile,
    eventsPath: eventsFile,
    sessionMapPath: sessionMap,
    stride: 1,
    clockSkew: { status: 'OK', uncertaintyMs: 2 }
  })
  assert.equal(analyzed.status, 'OK')
  assert.equal(analyzed.sessions['10'].taskCompletionMs, 500)
  assert.equal(analyzed.sessions['10'].databaseToClientObservationMs, 100)
  assert.equal(analyzed.sessions['10'].statusSources.INFLIGHT_CACHE, 1)
})

test('takeover verifier requires a new attempt, token and unique finalization', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'timeout-takeover-'))
  const events = path.join(directory, 'events.ndjson')
  const db = path.join(directory, 'db.tsv')
  const timeline = path.join(directory, 'fault.txt')
  const runtime = path.join(directory, 'runtime.log')
  fs.writeFileSync(events, [
    JSON.stringify({ eventType: 'CLAIM_HELD', examId: 1, taskId: 2, attemptCount: 1, claimTokenFingerprint: 'old', firstTargetClaim: true, instanceId: 'old-instance', wallClockEpochMs: 1000 }),
    JSON.stringify({ eventType: 'CLAIM_RECOVERY_OBSERVED', examId: 1, taskId: 2, attemptCount: 2, claimTokenFingerprint: 'new', firstTargetClaim: false, instanceId: 'new-instance', wallClockEpochMs: 2000 }),
    JSON.stringify({ eventType: 'FINALIZATION_COMMITTED', examId: 1, taskId: 2, attemptCount: 2, claimTokenFingerprint: 'new', firstTargetClaim: false, instanceId: 'new-instance', wallClockEpochMs: 3000 })
  ].join('\n') + '\n')
  fs.writeFileSync(db, 'task_id\tattempt_count\ttask_status\tsubmission_id\tfinal_payload_count\tlogical_event_count\tunique_final_payload_count\tunique_logical_event_count\n2\t2\tDONE\t3\t1\t1\t1\t1\n')
  fs.writeFileSync(timeline, 'exam_id=1\ntarget_task_id=2\nclaim_attempt=1\nclaim_token_fingerprint=old\nregistry_count_at_kill=4\nkill_started_at=1970-01-01T00:00:01.000Z\nkilled_at=1970-01-01T00:00:01.500Z\nsurvivors_untouched=true\ninjection_condition=met\nkill_on=CLAIM_HELD\n')
  fs.writeFileSync(runtime, 'shardTotal=4, claimed=1, completed=1\n')
  const result = verifyTakeover({
    eventsPath: events, dbEvidencePath: db, faultTimelinePath: timeline,
    runtimeEventsPath: runtime, outputPath: path.join(directory, 'result.json')
  })
  assert.equal(result.status, 'PASS')
  assert.equal(result.takeover.newTokenEvidence, true)
})
