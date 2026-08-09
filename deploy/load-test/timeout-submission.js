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

if (!examId || !Number.isFinite(deadlineEpochMs) || deadlineEpochMs <= 0) {
  throw new Error('EXAM_ID and DEADLINE_EPOCH_MS are required')
}
if (!Array.isArray(tokens) || tokens.length < users) {
  throw new Error(`TOKENS_FILE must contain at least ${users} access tokens`)
}

const completionLatency = new Trend('timeout_submission_completion_latency', true)
const incomplete = new Counter('timeout_submission_incomplete')

export const options = {
  scenarios: {
    timeout_completion: {
      executor: 'shared-iterations',
      vus: users,
      iterations: users,
      maxDuration: '3m'
    }
  },
  thresholds: {
    timeout_submission_completion_latency: ['p(99)<30000', 'max<60000'],
    timeout_submission_incomplete: ['count==0'],
    checks: ['rate==1']
  }
}

export default function () {
  const index = exec.scenario.iterationInTest
  const token = tokens[index]
  const headers = { Authorization: `Bearer ${token}` }
  while (Date.now() < deadlineEpochMs) {
    sleep(Math.min(0.5, Math.max(0.01, (deadlineEpochMs - Date.now()) / 1000)))
  }

  const stopAt = deadlineEpochMs + 60000
  let submitted = false
  while (Date.now() <= stopAt) {
    const response = http.get(`${baseUrl}/exams/${examId}/submission-status`, {
      headers,
      tags: { name: 'submission-status' }
    })
    if (response.status === 200) {
      const body = response.json('data')
      if (body?.sessionStatus === 'SUBMITTED' && body?.submissionStatus === 'PROCESSING') {
        completionLatency.add(Date.now() - deadlineEpochMs)
        submitted = true
        break
      }
    }
    sleep(0.5)
  }

  if (!submitted) {
    incomplete.add(1)
  }
  check(submitted, { 'session completed within 60 seconds': (value) => value === true })
}
