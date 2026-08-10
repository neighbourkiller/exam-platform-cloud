import http from 'k6/http'
import exec from 'k6/execution'
import { check, sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'
import { SharedArray } from 'k6/data'

const tokenFile = __ENV.TOKENS_FILE || './tokens.json'
const tokens = new SharedArray('exam-entry-tokens', () => JSON.parse(open(tokenFile)))
const baseUrl = __ENV.BASE_URL || 'http://localhost:16730/api/v1'
const examId = __ENV.EXAM_ID
const users = Number(__ENV.USERS || 10000)
const rate = Number(__ENV.RATE || 1000)
const duration = __ENV.DURATION || '10s'
const dualDevice = String(__ENV.DUAL_DEVICE || 'false').toLowerCase() === 'true'

if (!examId) throw new Error('EXAM_ID is required')
if (!Array.isArray(tokens) || tokens.length < users) {
  throw new Error(`TOKENS_FILE must contain at least ${users} access tokens`)
}

const completeFlow = new Trend('exam_entry_complete_flow', true)
const slotAssignments = new Counter('exam_entry_slot_assignments')
const activateSuccess = new Rate('exam_entry_activate_success')

export const options = {
  scenarios: {
    exam_entry: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      gracefulStop: __ENV.GRACEFUL_STOP || '3m',
      preAllocatedVUs: Math.min(users, Number(__ENV.PREALLOCATED_VUS || 3000)),
      maxVUs: users
    }
  },
  thresholds: {
    checks: ['rate>=0.999'],
    exam_entry_activate_success: ['rate>=0.999'],
    'http_req_duration{name:entry-activate}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{name:paper-delivery}': ['p(95)<1000', 'p(99)<2000'],
    exam_entry_complete_flow: ['p(99)<3000']
  }
}

const headers = token => ({
  Authorization: `Bearer ${token}`,
  'Content-Type': 'application/json'
})

const parse = response => {
  try {
    return response.json()
  } catch {
    return null
  }
}

const retryable = response => {
  const body = parse(response)
  return response.status === 425 || response.status === 429 || response.status === 503
    || ['EXAM_ENTRY_NOT_READY', 'EXAM_ENTRY_BUSY', 'EXAM_PREPARING',
      'EXAM_PAPER_UNAVAILABLE', 'EXAM_ENTRY_UNAVAILABLE'].includes(body?.code)
}

const retryAfterMs = response => Number(parse(response)?.data?.retryAfterMs || 0)

const withRetry = (request, deadlineMs = 30000) => {
  const started = Date.now()
  let attempt = 0
  while (true) {
    const response = request()
    if (!retryable(response)) return response
    if (Date.now() - started >= deadlineMs) return response
    const cap = Math.min(2000, 200 * (2 ** Math.min(attempt, 5)))
    const serverDelay = retryAfterMs(response)
    const ceiling = serverDelay > 0 ? Math.min(cap, serverDelay) : cap
    sleep(Math.max(0.05, Math.random() * Math.max(1, ceiling) / 1000))
    attempt += 1
  }
}

export default function () {
  const index = exec.scenario.iterationInTest
  if (index >= users) return
  const token = tokens[index]
  const clientId = `k6-entry-${index}`
  const prepare = withRetry(() => http.post(
    `${baseUrl}/exams/${examId}/entry/prepare`,
    JSON.stringify({ clientId }),
    { headers: headers(token), tags: { name: 'entry-prepare' } }
  ))
  const prepared = parse(prepare)?.data
  if (!check(prepare, {
    'prepare returns a reusable ticket': response =>
      response.status === 200 && Boolean(prepared?.entryToken)
  })) return
  slotAssignments.add(1, { slot: String(prepared.slot) })

  const scheduledAt = Date.parse(prepared.scheduledActivationTime)
  const serverTime = Date.parse(prepared.serverTime)
  if (Number.isFinite(scheduledAt) && Number.isFinite(serverTime) && scheduledAt > serverTime) {
    sleep((scheduledAt - serverTime) / 1000)
  }
  const flowStarted = Date.now()
  const activate = withRetry(() => http.post(
    `${baseUrl}/exams/${examId}/entry/activate`,
    JSON.stringify({ clientId, entryToken: prepared.entryToken }),
    { headers: headers(token), tags: { name: 'entry-activate' } }
  ))
  const activated = parse(activate)?.data
  const activatedSuccessfully = activate.status === 200
    && ['STARTED', 'RESUMED'].includes(activated?.status)
  activateSuccess.add(activatedSuccessfully)
  if (!check(activate, {
    'activate starts or resumes one session': response =>
      response.status === 200 && ['STARTED', 'RESUMED'].includes(activated?.status)
  })) return

  const delivery = withRetry(() => http.post(
    `${baseUrl}/exams/${examId}/paper-delivery`,
    JSON.stringify({ clientId, leaseToken: activated.leaseToken }),
    { headers: headers(token), tags: { name: 'paper-delivery' } }
  ))
  check(delivery, {
    'paper delivery returns static questions': response =>
      response.status === 200 && Array.isArray(parse(response)?.data?.questions)
  })
  completeFlow.add(Date.now() - flowStarted)

  if (dualDevice) {
    const secondClientId = `${clientId}-second`
    const secondPrepare = http.post(
      `${baseUrl}/exams/${examId}/entry/prepare`,
      JSON.stringify({ clientId: secondClientId }),
      { headers: headers(token), tags: { name: 'entry-prepare-second-device' } }
    )
    const secondTicket = parse(secondPrepare)?.data?.entryToken
    if (secondTicket) {
      const conflict = http.post(
        `${baseUrl}/exams/${examId}/entry/activate`,
        JSON.stringify({ clientId: secondClientId, entryToken: secondTicket }),
        { headers: headers(token), tags: { name: 'entry-activate-second-device' } }
      )
      check(conflict, {
        'second device is rejected by authoritative lease': response =>
          response.status === 409 && parse(response)?.code === 'EXAM_CLIENT_CONFLICT'
      })
    }
  }
}
