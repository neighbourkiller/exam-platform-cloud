import fs from 'node:fs'

/**
 * 将 k6 的诊断指标流还原为按会话的最小时间线。
 * 输入只包含自定义指标标签，不保存请求头、令牌、答案或完整响应。
 */

export function extractDiagnostics({ inputPath, sessionMapPath, stride = 10 }) {
  const errors = []
  const warnings = []
  const mapping = readJson(sessionMapPath, errors, 'session map')
  const entries = Array.isArray(mapping?.entries)
    ? mapping.entries.filter(entry => entry.iteration % stride === 0)
    : []
  const sessions = new Map()
  for (const entry of entries) {
    const sessionId = String(entry.sessionId ?? '')
    if (!/^\d+$/.test(sessionId)) {
      errors.push(`诊断映射中的 session_id 非法: ${sessionId}`)
      continue
    }
    sessions.set(sessionId, {
      iteration: entry.iteration,
      studentId: String(entry.studentId),
      sessionId,
      taskId: String(entry.taskId),
      requests: [],
      waits: [],
      completion: null
    })
  }
  if (sessions.size === 0) errors.push('没有可用的诊断会话映射')

  if (!fs.existsSync(inputPath)) {
    errors.push(`k6 诊断输出不存在: ${inputPath}`)
  } else {
    const lines = fs.readFileSync(inputPath, 'utf8').split(/\r?\n/).filter(Boolean)
    for (const [index, line] of lines.entries()) {
      let point
      try {
        point = JSON.parse(line)
      } catch (error) {
        errors.push(`k6 诊断第 ${index + 1} 行不可解析: ${error.message}`)
        continue
      }
      const metric = String(point.metric ?? point.data?.metric ?? '')
      const data = point.data && typeof point.data === 'object' ? point.data : point
      const tags = data.tags && typeof data.tags === 'object' ? data.tags : {}
      const sessionId = String(tags.session_id ?? '')
      if (!sessionId || !sessions.has(sessionId)) continue
      const session = sessions.get(sessionId)
      const iteration = String(tags.iteration ?? session.iteration)

      if (metric === 'timeout_diagnostic_request_duration') {
        session.requests.push({
          iteration,
          requestKind: String(tags.request_kind ?? 'UNKNOWN'),
          statusCode: numberOrNull(tags.status_code),
          phase: String(tags.phase ?? 'UNKNOWN'),
          startedAtMs: numberOrNull(tags.started_epoch_ms),
          endedAtMs: numberOrNull(tags.ended_epoch_ms),
          durationMs: numberOrNull(data.value ?? tags.duration_ms)
        })
      } else if (metric === 'timeout_diagnostic_poll_wait') {
        session.waits.push({
          iteration,
          waitKind: String(tags.wait_kind ?? 'UNKNOWN'),
          startedAtMs: numberOrNull(tags.started_epoch_ms),
          endedAtMs: numberOrNull(tags.ended_epoch_ms),
          durationMs: numberOrNull(data.value ?? tags.duration_ms)
        })
      } else if (metric === 'timeout_diagnostic_session_completion') {
        const completed = String(tags.completed ?? '') === 'true'
        const completion = {
          completed,
          completedAtMs: numberOrNull(tags.completed_epoch_ms),
          conclusion: String(tags.conclusion ?? 'UNKNOWN'),
          value: numberOrNull(data.value)
        }
        if (!session.completion || (completed && !session.completion.completed)) {
          session.completion = completion
        }
      }
    }
  }

  const serialized = {}
  for (const [sessionId, session] of sessions) {
    session.requests.sort((a, b) => (a.startedAtMs ?? 0) - (b.startedAtMs ?? 0))
    session.waits.sort((a, b) => (a.startedAtMs ?? 0) - (b.startedAtMs ?? 0))
    if (!session.completion) warnings.push(`会话 ${sessionId} 缺少客户端完成结论`)
    serialized[sessionId] = session
  }
  return {
    status: errors.length === 0 ? 'OK' : 'ERROR',
    errors,
    warnings,
    stride,
    selectedCount: sessions.size,
    sessions: serialized,
    generatedAt: new Date().toISOString()
  }
}

function readJson(file, errors, label) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch (error) {
    errors.push(`${label} 不可读: ${error.message}`)
    return null
  }
}

function numberOrNull(value) {
  if (value == null || value === '' || value === 'null') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
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

if (process.argv[1]?.endsWith('/extract-timeout-diagnostics.mjs')) {
  try {
    const args = parseArgs(process.argv.slice(2))
    if (!args.input || !args['session-map'] || !args.output) {
      throw new Error('用法: extract-timeout-diagnostics.mjs --input FILE --session-map FILE --output FILE [--stride N]')
    }
    const stride = Number(args.stride ?? 10)
    if (!Number.isSafeInteger(stride) || stride < 1) throw new Error('stride 必须是正整数')
    const result = extractDiagnostics({
      inputPath: args.input,
      sessionMapPath: args['session-map'],
      stride
    })
    fs.writeFileSync(args.output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    process.exitCode = result.status === 'OK' ? 0 : 2
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  }
}
