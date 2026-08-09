import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createSubmissionPlan,
  isSubmissionAccepted,
  shouldRetryDeadlineWithAnswers,
  submissionPollDelay
} from '../src/utils/submissionFlow.js'

test('截止前在线交卷需要校验并同步完整答案', () => {
  assert.deepEqual(createSubmissionPlan({ online: true, deadlineReached: false }), {
    mode: 'ACTIVE_SUBMIT',
    validate: true,
    sync: true,
    stripAnswers: false
  })
})

test('本机截止后先发送最小接管请求并允许服务端未截止时补交完整答案', () => {
  for (const dirty of [false, true]) {
    assert.deepEqual(createSubmissionPlan({ online: true, deadlineReached: true, dirty }), {
      mode: 'SERVER_DEADLINE_HANDOFF',
      validate: false,
      sync: false,
      stripAnswers: true,
      retryWithAnswersWhenActive: true
    })
  }
})

test('离线截止等待联网后由服务端确认，不直接离页', () => {
  assert.equal(
    createSubmissionPlan({ online: false, deadlineReached: true }).mode,
    'OFFLINE_DEADLINE_WAIT'
  )
  assert.equal(
    createSubmissionPlan({ online: false, deadlineReached: false }).mode,
    'OFFLINE_WAIT'
  )
})

test('只有服务端提交状态变化才视为已接管', () => {
  assert.equal(isSubmissionAccepted({ status: 'SUBMITTING' }), true)
  assert.equal(isSubmissionAccepted({ sessionStatus: 'SUBMITTED' }), true)
  assert.equal(isSubmissionAccepted({ submissionStatus: 'PROCESSING' }), true)
  assert.equal(isSubmissionAccepted({
    sessionStatus: 'ANSWERING',
    timeoutTaskStatus: 'PENDING'
  }), false)
})

test('服务端尚未截止并拒绝空答案时改用完整答案提交', () => {
  const plan = createSubmissionPlan({ online: true, deadlineReached: true })
  assert.equal(shouldRetryDeadlineWithAnswers(plan, {
    responseData: { code: 'INVALID_EXAM_ANSWERS' }
  }), true)
  assert.equal(shouldRetryDeadlineWithAnswers(plan, {
    responseData: { code: 'EXAM_CLIENT_CONFLICT' }
  }), false)
})

test('状态轮询退避带有边界和抖动', () => {
  assert.equal(submissionPollDelay(1, 0), 1200)
  assert.equal(submissionPollDelay(1, 1), 1800)
  assert.ok(submissionPollDelay(20, 1) <= 6000)
})
