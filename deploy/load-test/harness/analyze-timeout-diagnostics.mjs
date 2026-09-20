import fs from 'node:fs'

/**
 * 将客户端诊断、MySQL 时间线、状态来源事件和时钟测量按 session_id 合并。
 * 这里的 completed_at 仍然明确标记为数据库记录时间，不冒充事务提交时间。
 */

export function analyzeDiagnostics({ client, databasePath, eventsPath, sessionMapPath, stride, clockSkew }) {
  const errors = []
  const warnings = []
  const clientValue = readJson(client, errors, 'client diagnostics')
  const mapping = readJson(sessionMapPath, errors, 'session map')
  const db = readDatabase(databasePath, errors)
  const events = readEvents(eventsPath, errors)
  const selected = Array.isArray(mapping?.entries)
    ? mapping.entries.filter(entry => entry.iteration % stride === 0)
    : []
  const clientSessions = clientValue?.sessions ?? {}
  const timelines = {}
  const latencyGroups = {
    taskCompletionMs: [],
    databaseToClientObservationMs: [],
    statusRequestMs: [],
    pollWaitMs: []
  }

  for (const entry of selected) {
    const sessionId = String(entry.sessionId)
    const row = db.get(sessionId)
    const session = clientSessions[sessionId]
    if (!row) {
      errors.push(`诊断会话 ${sessionId} 缺少数据库时间线`)
      continue
    }
    if (!session) {
      errors.push(`诊断会话 ${sessionId} 缺少客户端时间线`)
      continue
    }
    const completionAtMs = session.completion?.completedAtMs ?? null
    const taskCompletionMs = row.completedAtEpochMs != null && row.dueAtEpochMs != null
      ? row.completedAtEpochMs - row.dueAtEpochMs : null
    const databaseToClientObservationMs = completionAtMs != null && row.completedAtEpochMs != null
      ? completionAtMs - row.completedAtEpochMs : null
    const requestDurations = session.requests.map(item => item.durationMs).filter(finite)
    const waitDurations = session.waits.map(item => item.durationMs).filter(finite)
    const finalRequest = session.requests
      .filter(item => item.phase === 'RUNTIME_FINALIZED')
      .sort((a, b) => (a.endedAtMs ?? 0) - (b.endedAtMs ?? 0))[0]
    const nonFinalizedAfterDb = session.requests
      .filter(item => row.completedAtEpochMs != null
        && item.endedAtMs != null && item.endedAtMs >= row.completedAtEpochMs
        && item.phase !== 'RUNTIME_FINALIZED')
    const statusStillNonFinalizedMs = finalRequest && row.completedAtEpochMs != null
      ? Math.max(0, finalRequest.endedAtMs - row.completedAtEpochMs) : null
    const sources = events.get(sessionId) ?? []
    const sourceCounts = {}
    for (const event of sources) sourceCounts[event.statusSource] = (sourceCounts[event.statusSource] ?? 0) + 1

    const timeline = {
      sessionId,
      studentId: String(entry.studentId),
      taskId: String(entry.taskId),
      dueAtEpochMs: row.dueAtEpochMs,
      completedAtEpochMs: row.completedAtEpochMs,
      attemptCount: row.attemptCount,
      taskStatus: row.taskStatus,
      sessionStatus: row.sessionStatus,
      submissionId: row.submissionId,
      clientCompletedAtEpochMs: completionAtMs,
      clientConclusion: session.completion?.conclusion ?? 'MISSING',
      requestCount: session.requests.length,
      requestDurationMs: distribution(requestDurations),
      pollWaitMs: distribution(waitDurations),
      databaseToClientObservationMs,
      taskCompletionMs,
      statusStillNonFinalizedMs,
      nonFinalizedRequestsAfterDatabaseCompletion: nonFinalizedAfterDb.length,
      statusSources: sourceCounts,
      statusEvents: sources,
      classification: classify({
        taskCompletionMs,
        databaseToClientObservationMs,
        requestDurations,
        waitDurations,
        statusStillNonFinalizedMs
      })
    }
    timelines[sessionId] = timeline
    addIfFinite(latencyGroups.taskCompletionMs, taskCompletionMs)
    addIfFinite(latencyGroups.databaseToClientObservationMs, databaseToClientObservationMs)
    for (const value of requestDurations) addIfFinite(latencyGroups.statusRequestMs, value)
    for (const value of waitDurations) addIfFinite(latencyGroups.pollWaitMs, value)
  }

  const missing = selected.length - Object.keys(timelines).length
  if (missing > 0) warnings.push(`有 ${missing} 个抽样会话未能形成完整时间线`)
  const status = errors.length === 0 && clientValue?.status === 'OK' ? 'OK' : 'ERROR'
  return {
    status,
    errors,
    warnings,
    stride,
    selectedCount: selected.length,
    completeCount: Object.keys(timelines).length,
    clockSkew: clockSkew ?? null,
    distributions: Object.fromEntries(
      Object.entries(latencyGroups).map(([key, values]) => [key, distribution(values)])
    ),
    sessions: timelines,
    generatedAt: new Date().toISOString()
  }
}

function classify({ taskCompletionMs, databaseToClientObservationMs, requestDurations, waitDurations, statusStillNonFinalizedMs }) {
  if (waitDurations.length > 0 && sum(waitDurations) > sum(requestDurations)) return 'POLL_WAIT_DOMINANT'
  if (statusStillNonFinalizedMs != null && statusStillNonFinalizedMs > 0) return 'DATABASE_DONE_BUT_STATUS_NOT_FINALIZED'
  if (requestDurations.length > 0 && Math.max(...requestDurations) > 1000) return 'STATUS_REQUEST_PROCESSING_SLOW'
  if (taskCompletionMs != null && taskCompletionMs > 0) return 'TASK_FINALIZATION_OR_LEASE_RECOVERY'
  if (databaseToClientObservationMs != null && databaseToClientObservationMs > 0) return 'POST_DATABASE_OBSERVATION_WAIT'
  return 'UNCLASSIFIED'
}

function readDatabase(file, errors) {
  const result = new Map()
  try {
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean)
    const header = lines.shift()?.split('\t') ?? []
    for (const line of lines) {
      const values = line.split('\t')
      const row = Object.fromEntries(header.map((key, index) => [key, values[index] ?? '']))
      if (!/^\d+$/.test(row.session_id ?? '')) continue
      result.set(String(row.session_id), {
        dueAtEpochMs: numberOrNull(row.due_at_epoch_ms),
        completedAtEpochMs: numberOrNull(row.completed_at_epoch_ms),
        attemptCount: numberOrNull(row.attempt_count),
        taskStatus: row.task_status || null,
        sessionStatus: row.session_status || null,
        submissionId: numberOrNull(row.submission_id)
      })
    }
  } catch (error) {
    errors.push(`数据库诊断时间线不可读: ${error.message}`)
  }
  return result
}

function readEvents(file, errors) {
  const result = new Map()
  try {
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean)) {
      const event = JSON.parse(line)
      if (event.eventType !== 'STATUS_LOOKUP' || event.sessionId == null) continue
      const key = String(event.sessionId)
      if (!result.has(key)) result.set(key, [])
      result.get(key).push({
        statusSource: event.statusSource ?? 'UNKNOWN',
        runtimeFinalized: event.runtimeFinalized === true,
        wallClockEpochMs: numberOrNull(event.wallClockEpochMs),
        elapsedNanos: numberOrNull(event.elapsedNanos)
      })
    }
  } catch (error) {
    errors.push(`状态来源事件不可读: ${error.message}`)
  }
  return result
}

function readJson(file, errors, label) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch (error) {
    errors.push(`${label} 不可读: ${error.message}`)
    return null
  }
}

function finite(value) { return typeof value === 'number' && Number.isFinite(value) }
function addIfFinite(values, value) { if (finite(value)) values.push(value) }
function sum(values) { return values.reduce((total, value) => total + value, 0) }
function numberOrNull(value) {
  if (value == null || value === '' || value === 'NULL' || value === 'null') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}
function distribution(values) {
  const sorted = values.filter(finite).sort((a, b) => a - b)
  if (sorted.length === 0) return { count: 0, minMs: null, avgMs: null, p50Ms: null, p90Ms: null, p99Ms: null, maxMs: null }
  const pick = fraction => sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)]
  return {
    count: sorted.length,
    minMs: sorted[0],
    avgMs: sum(sorted) / sorted.length,
    p50Ms: pick(0.5),
    p90Ms: pick(0.9),
    p99Ms: pick(0.99),
    maxMs: sorted.at(-1)
  }
}

function parseArgs(argv) {
  const args = {}
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index]
    if (!flag.startsWith('--')) throw new Error(`未知参数: ${flag}`)
    const value = argv[index + 1]
    if (value == null || value.startsWith('--')) throw new Error(`${flag} 缺少值`)
    args[flag.slice(2)] = value
    index += 1
  }
  return args
}

if (process.argv[1]?.endsWith('/analyze-timeout-diagnostics.mjs')) {
  try {
    const args = parseArgs(process.argv.slice(2))
    for (const key of ['client', 'database', 'events', 'session-map', 'output']) {
      if (!args[key]) throw new Error(`缺少 --${key}`)
    }
    const stride = Number(args.stride ?? 10)
    const clockSkew = args['clock-skew'] && fs.existsSync(args['clock-skew'])
      ? JSON.parse(fs.readFileSync(args['clock-skew'], 'utf8')) : null
    const result = analyzeDiagnostics({
      client: args.client,
      databasePath: args.database,
      eventsPath: args.events,
      sessionMapPath: args['session-map'],
      stride,
      clockSkew
    })
    fs.writeFileSync(args.output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    process.exitCode = result.status === 'OK' ? 0 : 2
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  }
}
