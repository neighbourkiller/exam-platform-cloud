import fs from 'node:fs'

const REQUIRED_STATES = ['resource', 'registry', 'prometheus']

export function validateSampling({
  stateDir,
  noResidualProcesses = true,
  filesStable = true,
  faultTarget = false,
  faultTargetInstance = null,
  faultKilledAtMs = null,
  maxMissingMs = 15000
}) {
  const errors = []
  const warnings = []
  const samplers = {}

  for (const name of REQUIRED_STATES) {
    const statePath = `${stateDir}/${name}.state.json`
    const outputPath = `${stateDir}/${name}.samples`
    let state = null
    try {
      state = JSON.parse(fs.readFileSync(statePath, 'utf8'))
    } catch (error) {
      errors.push(`${name} 状态文件不可读: ${error.message}`)
    }

    const outputExists = fs.existsSync(outputPath)
    const outputBytes = outputExists ? fs.statSync(outputPath).size : 0
    const stateErrors = []
    if (!state || typeof state !== 'object') {
      stateErrors.push('状态不是对象')
    } else {
      if (state.started !== true) stateErrors.push('started 不是 true')
      if (state.stopped !== true) stateErrors.push('stopped 不是 true')
      if (state.ready !== true) stateErrors.push('ready 不是 true')
      if (!Number.isSafeInteger(state.validSamples) || state.validSamples <= 0) {
        stateErrors.push('validSamples 必须为正整数')
      }
      if (!Number.isSafeInteger(state.errorCount) || state.errorCount < 0) {
        stateErrors.push('errorCount 类型错误')
      }
    if (name === 'prometheus') {
        if (state.hasHikari !== true) stateErrors.push('未观测到 Hikari 指标')
        if (state.hasJvm !== true) stateErrors.push('未观测到 JVM 指标')
        if (state.hasBacklog !== true) stateErrors.push('未观测到超时任务积压指标')
        if (faultTarget && Number(state.expectedInstances) > Number(state.activeInstances)) {
          warnings.push(`Prometheus 在故障轮允许目标实例缺样: ${state.expectedInstances} -> ${state.activeInstances}`)
        }
      }
    }
    if (!outputExists || outputBytes === 0) stateErrors.push('样本文件为空')
    const coverage = (name === 'prometheus' || name === 'resource')
      ? validateInstanceCoverage(outputPath, stateDir, name, {
        faultTarget,
        faultTargetInstance,
        faultKilledAtMs,
        maxMissingMs
      })
      : null
    if (coverage) {
      stateErrors.push(...coverage.errors)
      warnings.push(...coverage.warnings)
    }
    if (stateErrors.length > 0) errors.push(`${name}: ${stateErrors.join('；')}`)

    samplers[name] = {
      state,
      output: { exists: outputExists, bytes: outputBytes },
      errors: stateErrors,
      status: stateErrors.length === 0 ? 'PASS' : 'ERROR',
      coverage
    }
  }

  if (noResidualProcesses !== true) errors.push('采样进程或派生进程仍有残留')
  if (filesStable !== true) errors.push('采样文件在进程停止后仍继续增长')

  const status = errors.length === 0 ? 'PASS' : 'ERROR'
  return {
    status,
    errors,
    warnings,
    noResidualProcesses,
    filesStable,
    samplers,
    validatedAt: new Date().toISOString()
  }
}

function readExpectedInstances(stateDir, state) {
  const expectedFile = `${stateDir}/expected-instances.tsv`
  if (fs.existsSync(expectedFile)) {
    return fs.readFileSync(expectedFile, 'utf8')
      .split(/\r?\n/)
      .map(line => line.trim())
      .filter(Boolean)
      .map(line => {
        const [containerId, instanceId, containerName] = line.split('\t')
        return { containerId, instanceId, containerName }
      })
  }
  return Array.isArray(state?.expectedInstanceIds)
    ? state.expectedInstanceIds.map(instanceId => ({ instanceId }))
    : []
}

function parseSampleRows(outputPath) {
  if (!fs.existsSync(outputPath)) return []
  return fs.readFileSync(outputPath, 'utf8').split(/\r?\n/).filter(Boolean).map((line, index) => {
    const fields = line.split('\t')
    if (fields.length !== 9) {
      return { index, malformed: true, line }
    }
    const [sampleEpochMs, instanceId, containerId, requestStartedMs, requestEndedMs,
      requestDurationMs, metric, value, status] = fields
    return {
      index,
      malformed: false,
      sampleEpochMs: Number(sampleEpochMs),
      instanceId,
      containerId,
      requestStartedMs: Number(requestStartedMs),
      requestEndedMs: Number(requestEndedMs),
      requestDurationMs: Number(requestDurationMs),
      metric,
      value,
      status
    }
  })
}

function validateInstanceCoverage(outputPath, stateDir, name, options) {
  const errors = []
  const warnings = []
  const rows = parseSampleRows(outputPath)
  const statePath = `${stateDir}/${name}.state.json`
  let state = null
  try { state = JSON.parse(fs.readFileSync(statePath, 'utf8')) } catch {}
  const expected = readExpectedInstances(stateDir, state)
  if (expected.length === 0) {
    return { errors: ['固定预期实例集合缺失'], warnings, instances: {}, maxMissingMs: null }
  }

  const malformed = rows.filter(row => row.malformed)
  if (malformed.length > 0) errors.push(`${name} 存在缺少时间/实例字段的样本行: ${malformed.length}`)
  const byInstance = new Map(expected.map(item => [item.instanceId, []]))
  for (const row of rows) {
    if (!row.malformed && byInstance.has(row.instanceId)) byInstance.get(row.instanceId).push(row)
    if (!row.malformed && (!Number.isFinite(row.sampleEpochMs) || !row.instanceId
      || !Number.isFinite(row.requestStartedMs) || !Number.isFinite(row.requestEndedMs)
      || !Number.isFinite(row.requestDurationMs))) {
      errors.push(`${name} 存在时间/实例字段非法的样本行: ${row.index}`)
    }
  }

  const instances = {}
  for (const item of expected) {
    const instanceRows = byInstance.get(item.instanceId) || []
    const validEpochs = name === 'prometheus'
      ? prometheusValidEpochs(instanceRows)
      : new Set(instanceRows.filter(row => row.status === 'OK').map(row => row.sampleEpochMs))
    const sorted = [...validEpochs].filter(Number.isFinite).sort((a, b) => a - b)
    const gaps = []
    for (let index = 1; index < sorted.length; index += 1) {
      const gap = sorted[index] - sorted[index - 1]
      if (gap > options.maxMissingMs) gaps.push({ startMs: sorted[index - 1], endMs: sorted[index], gapMs: gap })
    }
    const allowedAfterKill = options.faultTarget && item.instanceId === options.faultTargetInstance
      && Number.isFinite(options.faultKilledAtMs)
    const disallowedGaps = gaps.filter(gap => {
      if (!allowedAfterKill) return true
      // kill 前的缺样仍然必须小于阈值；kill 之后的区间由故障目标豁免。
      const preKillEnd = Math.min(gap.endMs, options.faultKilledAtMs)
      return preKillEnd - gap.startMs > options.maxMissingMs
    })
    if (sorted.length === 0 && !allowedAfterKill) errors.push(`${name} 实例 ${item.instanceId} 没有有效样本`)
    if (disallowedGaps.length > 0) {
      errors.push(`${name} 实例 ${item.instanceId} 连续缺样超过 ${options.maxMissingMs}ms: ${JSON.stringify(disallowedGaps)}`)
    }
    const durations = instanceRows.map(row => row.requestDurationMs).filter(Number.isFinite)
    instances[item.instanceId] = {
      containerId: item.containerId || null,
      containerName: item.containerName || null,
      sampleCount: instanceRows.length,
      validSampleCount: sorted.length,
      firstValidSampleMs: sorted[0] ?? null,
      lastValidSampleMs: sorted.at(-1) ?? null,
      maxMissingMs: gaps.length ? Math.max(...gaps.map(gap => gap.gapMs)) : 0,
      missingIntervals: disallowedGaps,
      requestDurationMaxMs: durations.length ? Math.max(...durations) : null,
      requestDurationP99Ms: percentile(durations, 0.99)
    }
  }
  return { errors, warnings, instances, expectedInstances: expected.length }
}

function prometheusValidEpochs(rows) {
  const groups = new Map()
  for (const row of rows) {
    if (!groups.has(row.sampleEpochMs)) groups.set(row.sampleEpochMs, [])
    groups.get(row.sampleEpochMs).push(row)
  }
  const valid = new Set()
  for (const [epoch, group] of groups) {
    const successful = group.filter(row => row.status === 'OK')
    const names = new Set(successful.map(row => row.metric))
    const hikari = [...names].some(name => name.startsWith('hikaricp_connections_'))
    const jvm = names.has('jvm_memory_used_bytes')
    const backlog = [...names].some(name => name.startsWith('exam_timeout_submission_'))
    if (hikari && jvm && backlog) valid.add(epoch)
  }
  return valid
}

function percentile(values, fraction) {
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)]
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

try {
  if (process.argv[1] && process.argv[1].endsWith('/validate-sampling.mjs')) {
    const args = parseArgs(process.argv.slice(2))
    if (!args['state-dir'] || !args.output) {
      throw new Error('用法: validate-sampling.mjs --state-dir DIR --output FILE [--no-residual-processes true|false] [--files-stable true|false] [--fault-target true|false] [--fault-target-instance ID] [--fault-killed-at-ms MS]')
    }
    const result = validateSampling({
      stateDir: args['state-dir'],
      noResidualProcesses: args['no-residual-processes'] !== 'false',
      filesStable: args['files-stable'] !== 'false',
      faultTarget: args['fault-target'] === 'true',
      faultTargetInstance: args['fault-target-instance'] || null,
      faultKilledAtMs: args['fault-killed-at-ms'] ? Number(args['fault-killed-at-ms']) : null
    })
    fs.writeFileSync(args.output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
    const lines = [
      `sampling=${result.status}`,
      ...Object.entries(result.samplers).map(([name, item]) =>
        `${name}=${item.status} validSamples=${item.state?.validSamples ?? 'null'} errors=${item.state?.errorCount ?? 'null'}`),
      `no-residual-processes=${result.noResidualProcesses ? 'PASS' : 'ERROR'}`,
      `files-stable=${result.filesStable ? 'PASS' : 'ERROR'}`,
      ...result.errors.map(error => `error=${error}`),
      ...result.warnings.map(warning => `warning=${warning}`)
    ]
    process.stdout.write(`${lines.join('\n')}\n`)
    process.exitCode = result.status === 'PASS' ? 0 : 2
  }
} catch (error) {
  process.stderr.write(`${error.message}\n`)
  process.exitCode = 2
}
