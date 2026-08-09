import http from 'k6/http'
import exec from 'k6/execution'
import { SharedArray } from 'k6/data'
import { check } from 'k6'

const tokenFile = __ENV.TOKENS_FILE || './tokens.json'
const tokens = new SharedArray('submission-status-tokens', () => JSON.parse(open(tokenFile)))
const examId = __ENV.EXAM_ID
const baseUrl = __ENV.BASE_URL || 'http://localhost:16730/api/v1'
const users = Number(__ENV.USERS || 10000)

if (!examId) {
  throw new Error('EXAM_ID is required')
}
if (!Array.isArray(tokens) || tokens.length < users) {
  throw new Error(`TOKENS_FILE must contain at least ${users} access tokens`)
}

export const options = {
  scenarios: {
    submission_status_burst: {
      executor: 'shared-iterations',
      vus: users,
      iterations: users,
      maxDuration: '2m'
    }
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0']
  }
}

export default function () {
  const index = exec.scenario.iterationInTest
  const response = http.get(`${baseUrl}/exams/${examId}/submission-status`, {
    headers: { Authorization: `Bearer ${tokens[index]}` },
    tags: { name: 'submission-status' }
  })
  const body = response.status === 200 ? response.json('data') : null
  check(response, {
    'status endpoint returns 200': value => value.status === 200,
    'status is submitted and processing': () =>
      body?.sessionStatus === 'SUBMITTED' && body?.submissionStatus === 'PROCESSING'
  })
}
