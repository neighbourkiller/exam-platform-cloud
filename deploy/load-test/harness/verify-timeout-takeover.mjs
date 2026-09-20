import fs from 'node:fs'

/**
 * 校验确定性观察轮的目标任务接管证据。旧令牌在 DONE 后会被清空，
 * 因此新令牌必须来自恢复领取事件，而不能从最终任务行臆测。
 */

export function verifyTakeover({ eventsPath, dbEvidencePath, faultTimelinePath, runtimeEventsPath, outputPath }) {
  const errors = []
  const warnings = []
  const events = readEvents(eventsPath, errors)
  const timeline = readProperties(faultTimelinePath, errors)
  const db = readDbEvidence(dbEvidencePath, errors)
  const targetTaskId = String(timeline.target_task_id ?? '')
  const examId = String(timeline.exam_id ?? '')
  const initialAttempt = numberOrNull(timeline.claim_attempt)
  const initialFingerprint = timeline.claim_token_fingerprint || null
  const targetEvents = events.filter(event => String(event.taskId) === targetTaskId
    && String(event.examId) === examId)
  const firstClaims = targetEvents.filter(event => event.eventType === 'CLAIM_HELD'
    && event.firstTargetClaim === true)
  const firstClaim = firstClaims[0]
  const recoveryClaims = targetEvents.filter(event => event.eventType === 'CLAIM_RECOVERY_OBSERVED'
    && numberOrNull(event.attemptCount) > initialAttempt
    && event.claimTokenFingerprint && event.claimTokenFingerprint !== initialFingerprint)
  const finalizations = targetEvents.filter(event => event.eventType === 'FINALIZATION_COMMITTED'
    && numberOrNull(event.attemptCount) > initialAttempt)
  const observedShardTotals = readShardTotals(runtimeEventsPath, errors)
  const registrationCountAtKill = numberOrNull(timeline.registry_count_at_kill)
  const killedAt = parseTime(timeline.killed_at)
  const killStartedAt = parseTime(timeline.kill_started_at)
  const firstRecovery = recoveryClaims[0] || null
  const finalization = finalizations[0] || null

  if (!targetTaskId || !/^\d+$/.test(targetTaskId)) errors.push('fault-timeline 缺少合法 target_task_id')
  if (!examId || !/^\d+$/.test(examId)) errors.push('fault-timeline 缺少合法 exam_id')
  if (!Number.isSafeInteger(initialAttempt)) errors.push('缺少首次领取 attempt_count')
  if (!initialFingerprint) errors.push('缺少首次领取令牌指纹')
  if (!firstClaim) errors.push('缺少目标任务首次 CLAIM_HELD 事件')
  if (firstClaims.length > 1) errors.push(`目标任务首次 CLAIM_HELD 事件不是 1 条: ${firstClaims.length}`)
  if (recoveryClaims.length === 0) errors.push('缺少目标任务新领取轮次或新令牌指纹')
  if (finalizations.length !== 1) errors.push(`目标任务最终化事件数量不是 1: ${finalizations.length}`)
  if (!Number.isFinite(killStartedAt)) errors.push('缺少合法 kill_started_at')
  if (!Number.isFinite(killedAt)) errors.push('缺少合法 killed_at')
  if (Number.isFinite(killStartedAt) && Number.isFinite(killedAt) && killedAt < killStartedAt) {
    errors.push('killed_at 早于 kill_started_at')
  }
  if (timeline.survivors_untouched !== 'true') errors.push('未证明存活实例未被暂停或操作')
  if (timeline.injection_condition !== 'met') errors.push(`注入条件不是 met: ${timeline.injection_condition || 'missing'}`)
  if (registrationCountAtKill == null || registrationCountAtKill < 1) {
    errors.push('缺少 kill 时实际注册数量')
  }
  if (observedShardTotals.length === 0) errors.push('runtime-events 缺少实际 shardTotal 日志')
  if (firstClaim && Number(firstClaim.attemptCount) !== initialAttempt) {
    errors.push('首次 CLAIM_HELD 的 attempt_count 与时间线不一致')
  }
  if (firstRecovery && firstClaim && firstRecovery.instanceId === firstClaim.instanceId) {
    errors.push('恢复领取仍来自被杀实例，未证明由其他实例接管')
  }
  if (firstRecovery && finalization && Number(firstRecovery.attemptCount) !== Number(finalization.attemptCount)) {
    errors.push('最终化事件没有对应恢复领取轮次')
  }
  if (firstRecovery && finalization
    && firstRecovery.claimTokenFingerprint !== finalization.claimTokenFingerprint) {
    errors.push('最终化事件没有对应恢复领取令牌指纹')
  }
  if (timeline.kill_on === 'RENEW_SUCCEEDED') {
    const initialLease = numberOrNull(timeline.initial_lease_epoch_ms)
    const renewedLease = numberOrNull(timeline.renewed_lease_epoch_ms)
    if (initialLease == null || renewedLease == null || renewedLease <= initialLease) {
      errors.push('续租场景没有证明 lease_until 相对 CLAIM_HELD 初始值延长')
    }
    if (!Number.isFinite(parseTime(timeline.renew_succeeded_at_epoch_ms))) {
      errors.push('续租场景缺少 RENEW_SUCCEEDED 时间')
    }
  }
  if (!db) errors.push('缺少目标任务数据库终态证据')
  if (db && String(db.taskId) !== targetTaskId) errors.push('数据库终态证据 task_id 与目标任务不一致')
  if (db && db.taskStatus !== 'DONE') errors.push(`目标任务最终状态不是 DONE: ${db.taskStatus}`)
  if (db && (!Number.isSafeInteger(db.attemptCount) || db.attemptCount <= initialAttempt)) {
    errors.push(`目标任务没有新的领取轮次: initial=${initialAttempt}, final=${db.attemptCount}`)
  }
  if (db && db.finalPayloadCount !== 1) errors.push(`目标任务最终载荷数量不是 1: ${db.finalPayloadCount}`)
  if (db && db.logicalEventCount !== 1) errors.push(`目标任务逻辑交卷事件数量不是 1: ${db.logicalEventCount}`)
  if (db && db.uniqueFinalPayloadCount !== 1) errors.push(`目标任务唯一最终载荷数量不是 1: ${db.uniqueFinalPayloadCount}`)
  if (db && db.uniqueLogicalEventCount !== 1) errors.push(`目标任务唯一逻辑事件数量不是 1: ${db.uniqueLogicalEventCount}`)
  if (observedShardTotals.length === 0) warnings.push('runtime-events 未找到实际 shardTotal 日志，只有配置层预期值')
  if (registrationCountAtKill == null) warnings.push('fault-timeline 未记录 kill 时注册数量')

  const result = {
    status: errors.length === 0 ? 'PASS' : 'ERROR',
    errors,
    warnings,
    target: {
      examId: examId || null,
      taskId: targetTaskId || null,
      initialAttempt,
      initialTokenFingerprint: initialFingerprint,
      recoveryAttempt: recoveryClaims[0]?.attemptCount ?? null,
      recoveryTokenFingerprint: recoveryClaims[0]?.claimTokenFingerprint ?? null,
      firstClaimInstanceId: firstClaim?.instanceId ?? null,
      recoveryInstanceId: firstRecovery?.instanceId ?? null,
      claimHeldAtEpochMs: numberOrNull(firstClaim?.wallClockEpochMs),
      recoveryClaimAtEpochMs: numberOrNull(firstRecovery?.wallClockEpochMs),
      finalizationAtEpochMs: numberOrNull(finalization?.wallClockEpochMs),
      finalizationCount: finalizations.length,
      finalizationAttempt: finalizations[0]?.attemptCount ?? null,
      database: db
    },
    takeover: {
      newClaimEvidence: recoveryClaims.length > 0,
      newTokenEvidence: recoveryClaims.length > 0,
      uniqueFinalPayload: db?.uniqueFinalPayloadCount === 1,
      uniqueLogicalEvent: db?.uniqueLogicalEventCount === 1,
      registrationCountAtKill,
      observedShardTotals
    },
    generatedAt: new Date().toISOString()
  }
  if (outputPath) fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
  return result
}

function readEvents(file, errors) {
  try {
    return fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line))
  } catch (error) {
    errors.push(`观察事件不可读: ${error.message}`)
    return []
  }
}

function readProperties(file, errors) {
  const result = {}
  try {
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
      const separator = line.indexOf('=')
      if (separator > 0) result[line.slice(0, separator)] = line.slice(separator + 1)
    }
  } catch (error) {
    errors.push(`故障时间线不可读: ${error.message}`)
  }
  return result
}

function readDbEvidence(file, errors) {
  try {
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean)
    const values = lines.at(-1).split('\t')
    if (values.length < 8) throw new Error('字段数不足')
    return {
      taskId: values[0],
      attemptCount: numberOrNull(values[1]),
      taskStatus: values[2],
      submissionId: numberOrNull(values[3]),
      finalPayloadCount: numberOrNull(values[4]),
      logicalEventCount: numberOrNull(values[5]),
      uniqueFinalPayloadCount: numberOrNull(values[6]),
      uniqueLogicalEventCount: numberOrNull(values[7])
    }
  } catch (error) {
    errors.push(`接管数据库证据不可读: ${error.message}`)
    return null
  }
}

function readShardTotals(file, errors) {
  try {
    const text = fs.readFileSync(file, 'utf8')
    return [...text.matchAll(/shardTotal=(\d+)/g)].map(match => Number(match[1]))
  } catch (error) {
    errors.push(`Runtime 事件日志不可读: ${error.message}`)
    return []
  }
}

function parseTime(value) {
  if (value == null || value === '') return NaN
  if (/^\d+$/.test(String(value))) return Number(value)
  const parsed = Date.parse(String(value))
  return Number.isFinite(parsed) ? parsed : NaN
}

function numberOrNull(value) {
  if (value == null || value === '') return null
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

if (process.argv[1]?.endsWith('/verify-timeout-takeover.mjs')) {
  try {
    const args = parseArgs(process.argv.slice(2))
    for (const key of ['events', 'db-evidence', 'fault-timeline', 'runtime-events', 'output']) {
      if (!args[key]) throw new Error(`缺少 --${key}`)
    }
    const result = verifyTakeover({
      eventsPath: args.events,
      dbEvidencePath: args['db-evidence'],
      faultTimelinePath: args['fault-timeline'],
      runtimeEventsPath: args['runtime-events'],
      outputPath: args.output
    })
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    process.exitCode = result.status === 'PASS' ? 0 : 2
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  }
}
