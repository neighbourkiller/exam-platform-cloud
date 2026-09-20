import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

const THIS_FILE = fileURLToPath(import.meta.url)

export function parseK6Summary(summary, expectedIterations) {
  const errors = []
  const warnings = []
  const metrics = summary && typeof summary === 'object' && !Array.isArray(summary)
    ? summary.metrics
    : null

  if (!metrics || typeof metrics !== 'object' || Array.isArray(metrics)) {
    errors.push('summary.metrics 缺失或不是对象')
  }

  const readMetric = name => {
    const value = metrics?.[name]
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      errors.push(`metrics.${name} 缺失或不是对象`)
      return null
    }
    return value
  }

  const readFinite = (object, property, label) => {
    const value = object?.[property]
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      errors.push(`${label} 缺失、类型错误或不是有限数值`)
      return null
    }
    return value
  }

  const latency = readMetric('timeout_submission_completion_latency')
  const incomplete = readMetric('timeout_submission_incomplete')
  const iterations = readMetric('iterations')
  const checks = readMetric('checks')
  const httpFailed = metrics?.http_req_failed

  const latencyValues = {
    avgMs: latency ? readFinite(latency, 'avg', 'completion_latency.avg') : null,
    p90Ms: latency ? readFinite(latency, 'p(90)', 'completion_latency.p(90)') : null,
    p99Ms: latency ? readFinite(latency, 'p(99)', 'completion_latency.p(99)') : null,
    maxMs: latency ? readFinite(latency, 'max', 'completion_latency.max') : null
  }
  const incompleteCount = incomplete
    ? readFinite(incomplete, 'count', 'timeout_submission_incomplete.count')
    : null
  const iterationCount = iterations
    ? readFinite(iterations, 'count', 'iterations.count')
    : null
  const checksPassed = checks ? readFinite(checks, 'passes', 'checks.passes') : null
  const checksFailed = checks ? readFinite(checks, 'fails', 'checks.fails') : null
  const checksValue = checks ? readFinite(checks, 'value', 'checks.value') : null

  const threshold = (metric, predicate, label) => {
    const thresholds = metric?.thresholds
    if (!thresholds || typeof thresholds !== 'object' || Array.isArray(thresholds)) {
      errors.push(`${label}.thresholds 缺失或不是对象`)
      return { expression: null, rawFailed: null, passed: false }
    }
    const expression = Object.keys(thresholds).find(predicate)
    if (!expression) {
      errors.push(`${label} 未找到必需阈值表达式`)
      return { expression: null, rawFailed: null, passed: false }
    }
    const rawFailed = thresholds[expression]
    if (typeof rawFailed !== 'boolean') {
      errors.push(`${label}.thresholds[${expression}] 不是布尔值`)
      return { expression, rawFailed: null, passed: false }
    }
    // k6 v2.2.0 的 summary-export 约定：false 表示阈值未越限，true 表示越限。
    return { expression, rawFailed, passed: rawFailed === false }
  }

  const thresholds = {
    latencyP99: threshold(
      latency,
      expression => expression.startsWith('p(99)<'),
      'completion_latency.p(99)'
    ),
    latencyMax: threshold(
      latency,
      expression => expression.startsWith('max<'),
      'completion_latency.max'
    ),
    incomplete: threshold(
      incomplete,
      expression => expression === 'count==0',
      'timeout_submission_incomplete.count'
    ),
    iterations: threshold(
      iterations,
      expression => expectedIterations == null
        ? expression.startsWith('count==')
        : expression === `count==${expectedIterations}`,
      'iterations.count'
    ),
    checks: threshold(
      checks,
      expression => expression === 'rate==1',
      'checks.rate'
    )
  }

  if (expectedIterations != null
    && (!Number.isSafeInteger(expectedIterations) || expectedIterations <= 0)) {
    errors.push('expectedIterations 必须是正整数')
  }

  const parsed = {
    status: errors.length === 0 ? 'OK' : 'ERROR',
    errors,
    warnings,
    metrics: {
      completionLatency: latencyValues,
      incompleteCount,
      iterationCount,
      checks: {
        passes: checksPassed,
        fails: checksFailed,
        value: checksValue
      },
      httpRequestsFailed: httpFailed && typeof httpFailed === 'object'
        ? {
            fails: typeof httpFailed.fails === 'number' ? httpFailed.fails : null,
            rate: typeof httpFailed.rate === 'number' ? httpFailed.rate : null
          }
        : null
    },
    thresholds,
    thresholdFailures: Object.values(thresholds).filter(item => item.rawFailed === true).length,
    expectedIterations: expectedIterations ?? null,
    thresholdSemantics: 'k6-v2.2.0-summary-export-false-is-passed'
  }

  return parsed
}

export function readAndParseK6Summary(inputPath, expectedIterations) {
  const errors = []
  let summary = null
  try {
    const content = fs.readFileSync(inputPath, 'utf8')
    if (content.trim() === '') {
      errors.push('k6 summary 文件为空')
    } else {
      summary = JSON.parse(content)
    }
  } catch (error) {
    errors.push(`无法读取或解析 k6 summary: ${error.message}`)
  }

  if (errors.length > 0) {
    return {
      status: 'ERROR',
      errors,
      warnings: [],
      metrics: {
        completionLatency: { avgMs: null, p90Ms: null, p99Ms: null, maxMs: null },
        incompleteCount: null,
        iterationCount: null,
        checks: { passes: null, fails: null, value: null },
        httpRequestsFailed: null
      },
      thresholds: {},
      thresholdFailures: null,
      expectedIterations: expectedIterations ?? null,
      thresholdSemantics: 'k6-v2.2.0-summary-export-false-is-passed'
    }
  }

  return parseK6Summary(summary, expectedIterations)
}

function parseArgs(argv) {
  const args = {}
  for (let index = 0; index < argv.length; index += 1) {
    const item = argv[index]
    if (!item.startsWith('--')) {
      throw new Error(`未知参数: ${item}`)
    }
    const key = item.slice(2)
    const value = argv[index + 1]
    if (!value || value.startsWith('--')) {
      throw new Error(`参数 ${item} 缺少值`)
    }
    args[key] = value
    index += 1
  }
  return args
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    const args = parseArgs(process.argv.slice(2))
    if (!args.input || !args.output) {
      throw new Error('用法: parse-k6-summary.mjs --input FILE --output FILE [--expected-iterations N]')
    }
    const expectedIterations = args['expected-iterations'] == null
      ? null
      : Number(args['expected-iterations'])
    const result = readAndParseK6Summary(args.input, expectedIterations)
    fs.writeFileSync(args.output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    process.exitCode = result.status === 'OK' ? 0 : 2
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  }
}
