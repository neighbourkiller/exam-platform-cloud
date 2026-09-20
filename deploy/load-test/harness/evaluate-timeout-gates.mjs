import fs from 'node:fs'
import { readAndParseK6Summary } from './parse-k6-summary.mjs'

const NUMBER_FIELDS = [
  ['p99Seconds', '数据库 P99'],
  ['maxSeconds', '数据库 max'],
  ['doneAt60', '60 秒窗口完成数'],
  ['failedCount', 'FAILED 数'],
  ['processingCount', 'PROCESSING 数'],
  ['nullSubmissionCount', 'NULL submission_id 数'],
  ['finalPayloadCount', '最终载荷数'],
  ['draftPayloadCount', '主草稿数'],
  ['submittedSessionCount', '已提交 session 数'],
  ['processingSubmissionCount', 'PROCESSING submission 数'],
  ['outboxAcceptedCount', 'SubmissionAccepted 事件数'],
  ['outboxFailedCount', 'Outbox FAILED 数'],
  ['retryCount', '重试数']
]

export function parseDatabaseSummary(text) {
  const errors = []
  const lines = String(text ?? '').split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  const latencyHeaderIndex = lines.findIndex(line =>
    line.startsWith('done_tasks\t') && line.includes('\tp99_s\t') && line.endsWith('\tmax_s'))
  const result = {
    p99Seconds: null,
    maxSeconds: null,
    doneTasks: null,
    submittedSessionCount: null,
    processingSubmissionCount: null,
    finalPayloadCount: null,
    draftPayloadCount: null,
    nullSubmissionCount: null,
    failedCount: null,
    processingCount: null,
    outboxAcceptedCount: null,
    outboxFailedCount: null
  }

  if (latencyHeaderIndex < 0 || !lines[latencyHeaderIndex + 1]) {
    errors.push('database-summary 缺少延迟表或数据行')
  } else {
    const header = lines[latencyHeaderIndex].split('\t')
    const row = lines[latencyHeaderIndex + 1].split('\t')
    const value = name => {
      const index = header.indexOf(name)
      return index >= 0 ? numberOrNull(row[index]) : null
    }
    result.doneTasks = value('done_tasks')
    result.p99Seconds = value('p99_s')
    result.maxSeconds = value('max_s')
    if (result.p99Seconds == null) errors.push('database-summary 缺少有限的 p99_s')
    if (result.maxSeconds == null) errors.push('database-summary 缺少有限的 max_s')
  }

  const assertionHeaderIndex = lines.findIndex(line =>
    line.startsWith('done_tasks\tsubmitted_sessions\tprocessing_submissions\t'))
  if (assertionHeaderIndex < 0 || !lines[assertionHeaderIndex + 1]) {
    errors.push('database-summary 缺少一致性断言表或数据行')
  } else {
    const header = lines[assertionHeaderIndex].split('\t')
    const row = lines[assertionHeaderIndex + 1].split('\t')
    const value = name => {
      const index = header.indexOf(name)
      return index >= 0 ? numberOrNull(row[index]) : null
    }
    result.submittedSessionCount = value('submitted_sessions')
    result.processingSubmissionCount = value('processing_submissions')
    result.finalPayloadCount = value('final_payloads')
    result.draftPayloadCount = value('draft_payloads')
    result.nullSubmissionCount = value('null_submission_ids')
    result.failedCount = value('failed_tasks')
    result.processingCount = value('lingering_processing_tasks')
    result.outboxAcceptedCount = value('outbox_accepted_events')
    result.outboxFailedCount = value('outbox_failed_events')
  }

  return { result, errors }
}

export function evaluateTimeoutGates(input) {
  const reasons = []
  const components = {}
  const users = input.users
  const database = { ...(input.database || {}) }
  const requiredDatabase = [
    ['p99Seconds', '数据库 P99 缺失或非法'],
    ['maxSeconds', '数据库 max 缺失或非法'],
    ['doneAt60', '60 秒窗口完成数缺失或非法'],
    ...NUMBER_FIELDS.slice(3)
  ]
  const databaseErrors = []
  const databaseFailures = []

  if (!Number.isSafeInteger(users) || users <= 0) databaseErrors.push('USERS 必须是正整数')
  for (const [field, label] of requiredDatabase) {
    if (!finiteNumber(database[field])) databaseErrors.push(label)
  }
  if (finiteNumber(database.doneAt60) && database.doneAt60 !== users) {
    databaseFailures.push(`60 秒窗口内完成 ${database.doneAt60}/${users}`)
  }
  if (finiteNumber(database.p99Seconds) && database.p99Seconds >= input.dbP99GateSeconds) {
    databaseFailures.push(`数据库 P99 ${database.p99Seconds}s 未满足 <${input.dbP99GateSeconds}s`)
  }
  if (finiteNumber(database.maxSeconds) && database.maxSeconds >= input.dbMaxGateSeconds) {
    databaseFailures.push(`数据库 max ${database.maxSeconds}s 未满足 <${input.dbMaxGateSeconds}s`)
  }
  const maxAllowedRetry = Math.floor(users * 5 / 1000)
  if (finiteNumber(database.retryCount) && database.retryCount > maxAllowedRetry) {
    databaseFailures.push(`重试数 ${database.retryCount} 超过 ${maxAllowedRetry}`)
  }
  const expectedCounts = [
    ['failedCount', 0, '存在 FAILED 任务'],
    ['processingCount', 0, '存在遗留 PROCESSING 任务'],
    ['nullSubmissionCount', 0, '存在 NULL submission_id'],
    ['finalPayloadCount', users, `最终载荷不是 ${users}`],
    ['draftPayloadCount', users, `主草稿不是 ${users}`],
    ['submittedSessionCount', users, `SUBMITTED session 不是 ${users}`],
    ['processingSubmissionCount', users, `PROCESSING submission 不是 ${users}`],
    ['outboxAcceptedCount', users, `SubmissionAccepted 事件不是 ${users}`],
    ['outboxFailedCount', 0, '存在 Outbox FAILED 事件']
  ]
  for (const [field, expected, label] of expectedCounts) {
    if (finiteNumber(database[field]) && database[field] !== expected) databaseFailures.push(label)
  }
  components.database = componentResult(databaseErrors, databaseFailures, {
    p99Seconds: database.p99Seconds ?? null,
    maxSeconds: database.maxSeconds ?? null,
    doneAt60: database.doneAt60 ?? null,
    doneAt30: database.doneAt30 ?? null,
    totalTasks: users,
    retryCount: database.retryCount ?? null
  })
  reasons.push(...databaseErrors, ...databaseFailures)

  const polling = input.enablePolling === true
  if (!polling) {
    components.client = {
      status: 'NOT_RUN',
      p99Ms: null,
      maxMs: null,
      incompleteCount: null,
      iterationCount: null,
      checks: null,
      thresholds: null,
      errors: [],
      failures: []
    }
  } else {
    const clientErrors = []
    const clientFailures = []
    const k6 = input.k6
    if (input.k6ContainerCompleted !== true) {
      clientErrors.push('k6 容器未确认已结束，不能把 Docker CLI 退出当作 k6 完成')
    }
    if (!k6 || k6.status !== 'OK') {
      clientErrors.push(...(k6?.errors || ['k6 summary 未解析']))
    } else {
      const clientMetrics = k6.metrics
      const latency = clientMetrics.completionLatency
      const p99Ms = latency.p99Ms
      const maxMs = latency.maxMs
      if (p99Ms >= input.dbP99GateSeconds * 1000) {
        clientFailures.push(`客户端 P99 ${p99Ms}ms 未满足 <${input.dbP99GateSeconds * 1000}ms`)
      }
      if (maxMs >= input.dbMaxGateSeconds * 1000) {
        clientFailures.push(`客户端 max ${maxMs}ms 未满足 <${input.dbMaxGateSeconds * 1000}ms`)
      }
      if (clientMetrics.incompleteCount !== 0) {
        clientFailures.push(`客户端未完成数为 ${clientMetrics.incompleteCount}`)
      }
      if (clientMetrics.iterationCount !== users) {
        clientFailures.push(`客户端迭代数为 ${clientMetrics.iterationCount}/${users}`)
      }
      if (clientMetrics.checks.fails !== 0 || clientMetrics.checks.value !== 1) {
        clientFailures.push(`客户端 checks 失败: fails=${clientMetrics.checks.fails}, value=${clientMetrics.checks.value}`)
      }
      const thresholdFailures = k6.thresholdFailures
      const exitCode = input.k6ExitCode
      if (!Number.isInteger(exitCode)) {
        clientErrors.push('k6 退出码尚未获取')
      } else if (exitCode === 0 && thresholdFailures > 0) {
        clientErrors.push('k6 退出码为 0，但 summary 明确存在失败阈值（证据矛盾）')
      } else if (exitCode !== 0 && thresholdFailures === 0) {
        clientErrors.push(`k6 退出码为 ${exitCode}，但 summary 没有失败阈值（证据矛盾）`)
      } else if (exitCode !== 0) {
        clientFailures.push(`k6 退出码为 ${exitCode}`)
      }
      components.client = componentResult(clientErrors, clientFailures, {
        p99Ms,
        maxMs,
        incompleteCount: clientMetrics.incompleteCount,
        iterationCount: clientMetrics.iterationCount,
        checks: clientMetrics.checks,
        thresholds: k6.thresholds,
        httpRequestsFailed: clientMetrics.httpRequestsFailed
      })
    }
    if (!components.client) {
      components.client = componentResult(clientErrors, clientFailures, {
        p99Ms: null,
        maxMs: null,
        incompleteCount: null,
        iterationCount: null,
        checks: null,
        thresholds: k6?.thresholds || null
      })
    }
    reasons.push(...clientErrors, ...clientFailures)
  }

  const observability = input.observability
  if (!observability || observability.status !== 'PASS') {
    const observabilityErrors = observability?.errors || ['采样完整性结果缺失']
    components.observability = {
      status: 'ERROR',
      errors: observabilityErrors,
      samplers: observability?.samplers || null
    }
    reasons.push(...observabilityErrors)
  } else {
    components.observability = { status: 'PASS', errors: [], samplers: observability.samplers }
  }

  if (input.diagnosticEnabled !== true) {
    components.diagnostics = { status: 'NOT_RUN', errors: [], warnings: [] }
  } else {
    const diagnostics = input.diagnosticAnalysis
    if (!diagnostics || diagnostics.status !== 'OK') {
      const diagnosticErrors = diagnostics?.errors || ['客户端诊断时间线缺失或未通过关联校验']
      components.diagnostics = { status: 'ERROR', errors: diagnosticErrors, warnings: diagnostics?.warnings || [] }
      reasons.push(...diagnosticErrors)
  } else {
    components.diagnostics = {
        status: 'PASS',
        errors: [],
        warnings: diagnostics.warnings || [],
        selectedCount: diagnostics.selectedCount,
        completeCount: diagnostics.completeCount,
        distributions: diagnostics.distributions
      }
    }
  }

  if (input.takeoverRequired !== true) {
    components.takeover = { status: 'NOT_RUN', errors: [], warnings: [] }
  } else {
    const takeover = input.takeoverEvidence
    if (!takeover || takeover.status !== 'PASS') {
      const takeoverErrors = takeover?.errors || ['确定性接管证据缺失或未通过校验']
      components.takeover = {
        status: 'ERROR',
        errors: takeoverErrors,
        warnings: takeover?.warnings || []
      }
      reasons.push(...takeoverErrors)
    } else {
      components.takeover = {
        status: 'PASS',
        errors: [],
        warnings: takeover.warnings || [],
        target: takeover.target,
        evidence: takeover.takeover
      }
    }
  }

  const faultRequired = input.fault?.required === true
  if (!faultRequired) {
    components.fault = { status: 'NOT_APPLICABLE', injected: false, recoveredInflight: null, errors: [] }
  } else if (input.fault.injectionValid !== true) {
    components.fault = {
      status: 'NOT_APPLICABLE',
      injected: false,
      recoveredInflight: input.fault.recoveredInflight ?? null,
      errors: ['指定故障注入条件未满足']
    }
    reasons.push('指定故障注入条件未满足')
  } else if (!finiteNumber(input.fault.recoveredInflight)
    || input.fault.recoveredInflight <= 0) {
    components.fault = {
      status: 'FAIL',
      injected: true,
      recoveredInflight: input.fault.recoveredInflight ?? null,
      errors: ['未证明注入时在途任务发生租约恢复']
    }
    reasons.push('未证明注入时在途任务发生租约恢复')
  } else {
    components.fault = {
      status: 'PASS',
      injected: true,
      recoveredInflight: input.fault.recoveredInflight,
      errors: []
    }
  }

  const statuses = Object.values(components).map(component => component.status)
  let overall = 'PASS'
  if (statuses.includes('ERROR')) {
    overall = 'ERROR'
  } else if (faultRequired && components.fault.status === 'NOT_APPLICABLE') {
    overall = 'NOT_APPLICABLE'
  } else if (statuses.includes('FAIL')) {
    overall = 'FAIL'
  }

  return {
    runId: input.metadata?.timestamp || input.runId || null,
    scenario: input.metadata?.scenario || input.scenario || null,
    examId: input.metadata?.examId || input.examId || null,
    image: {
      runtimeImageId: input.metadata?.runtimeImageId || null,
      runtimeImageDigest: input.metadata?.runtimeImageDigest || null,
      k6Image: input.metadata?.k6Image || null
    },
    parameters: input.metadata || {},
    k6ExitCode: input.k6ExitCode ?? null,
    suiteExitCode: input.suiteExitCode ?? null,
    database: components.database,
    client: components.client,
    fault: components.fault,
    observability: components.observability,
    diagnostics: components.diagnostics,
    overall,
    reasons,
    generatedAt: new Date().toISOString()
  }
}

function componentResult(errors, failures, details) {
  return {
    status: errors.length > 0 ? 'ERROR' : failures.length > 0 ? 'FAIL' : 'PASS',
    errors,
    failures,
    ...details
  }
}

function finiteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value)
}

function numberOrNull(value) {
  if (value == null || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function readJson(path) {
  return JSON.parse(fs.readFileSync(path, 'utf8'))
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

function bool(value) {
  return value === 'true'
}

if (process.argv[1]?.endsWith('/evaluate-timeout-gates.mjs')) {
  try {
    const args = parseArgs(process.argv.slice(2))
    if (!args.output) throw new Error('缺少 --output')
    let metadata = {}
    if (args.metadata) {
      try {
        metadata = readJson(args.metadata)
      } catch (error) {
        metadata = { _readError: `metadata 不可读: ${error.message}` }
      }
    }
    let databaseText = ''
    if (args['database-summary']) {
      try {
        databaseText = fs.readFileSync(args['database-summary'], 'utf8')
      } catch (error) {
        databaseText = `database-summary 不可读: ${error.message}`
      }
    }
    const parsedDatabase = parseDatabaseSummary(databaseText)
    const database = {
      ...parsedDatabase.result,
      doneAt30: numberOrNull(args['done-count-30']),
      doneAt60: numberOrNull(args['done-count-60']),
      retryCount: numberOrNull(args['retry-count'])
    }
    const expectedIterations = numberOrNull(args.users)
    const k6 = bool(args.polling) && args['k6-summary']
      ? readAndParseK6Summary(args['k6-summary'], expectedIterations)
      : null
    let sampling = null
    if (args['sampling-state']) {
      try {
        sampling = readJson(args['sampling-state'])
      } catch (error) {
        sampling = { status: 'ERROR', errors: [`采样状态不可读: ${error.message}`] }
      }
    }
    let diagnosticAnalysis = null
    if (args['diagnostic-analysis']) {
      try {
        diagnosticAnalysis = readJson(args['diagnostic-analysis'])
      } catch (error) {
        diagnosticAnalysis = { status: 'ERROR', errors: [`客户端诊断结果不可读: ${error.message}`] }
      }
    }
    let takeoverEvidence = null
    if (args['takeover-evidence']) {
      try {
        takeoverEvidence = readJson(args['takeover-evidence'])
      } catch (error) {
        takeoverEvidence = { status: 'ERROR', errors: [`接管证据不可读: ${error.message}`] }
      }
    }
    const k6ExitCode = args['k6-exit-code'] == null || args['k6-exit-code'] === 'NOT_OBTAINED'
      ? null
      : Number(args['k6-exit-code'])
    const result = evaluateTimeoutGates({
      metadata,
      users: numberOrNull(args.users),
      dbP99GateSeconds: numberOrNull(args['db-p99-gate-seconds']),
      dbMaxGateSeconds: numberOrNull(args['db-max-gate-seconds']),
      enablePolling: bool(args.polling),
      k6,
      k6ExitCode,
      k6ContainerCompleted: args['k6-container-completed'] === 'true',
      database,
      observability: sampling,
      diagnosticEnabled: bool(args['diagnostic-enabled']),
      diagnosticAnalysis,
      takeoverRequired: bool(args['takeover-required']),
      takeoverEvidence,
      fault: {
        required: bool(args['fault-required']),
        injectionValid: bool(args['fault-injection-valid']),
        recoveredInflight: numberOrNull(args['fault-recovered-inflight'])
      },
      suiteExitCode: numberOrNull(args['suite-exit-code'])
    })
    if (parsedDatabase.errors.length > 0) {
      result.database.status = 'ERROR'
      result.database.errors.push(...parsedDatabase.errors)
      result.overall = 'ERROR'
      result.reasons.push(...parsedDatabase.errors)
    }
    if (metadata._readError) {
      result.overall = 'ERROR'
      result.reasons.push(metadata._readError)
    }
    fs.writeFileSync(args.output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    process.exitCode = result.overall === 'PASS'
      ? 0
      : result.overall === 'FAIL'
        ? 1
        : result.overall === 'NOT_APPLICABLE'
          ? 4
          : 2
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  }
}
