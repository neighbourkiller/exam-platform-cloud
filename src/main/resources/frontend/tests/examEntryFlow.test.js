import test from 'node:test'
import assert from 'node:assert/strict'
import {
  fullJitterDelay,
  isRetryableEntryError,
  mergePaperDelivery,
  scheduledEntryDelayMs
} from '../src/utils/examEntryFlow.js'

test('425/429/503 入场错误会自动重试，功能关闭不会误重试', () => {
  assert.equal(isRetryableEntryError({ response: { status: 425 } }), true)
  assert.equal(isRetryableEntryError({ code: 'EXAM_ENTRY_BUSY', response: { status: 429 } }), true)
  assert.equal(isRetryableEntryError({ code: 'EXAM_PREPARING', response: { status: 503 } }), true)
  assert.equal(isRetryableEntryError({ code: 'EXAM_ENTRY_V2_DISABLED', response: { status: 503 } }), false)
})

test('全抖动退避从 200ms 档开始并把服务端等待时间作为下限', () => {
  assert.equal(fullJitterDelay(0, 0, () => 0.5), 100)
  assert.equal(fullJitterDelay(10, 0, () => 0.999), 1998)
  assert.equal(fullJitterDelay(3, 300, () => 0.5), 1100)
})

test('候场等待使用服务器时间差，不受浏览器本地时钟偏差影响', () => {
  assert.equal(scheduledEntryDelayMs({
    serverTime: '2026-08-09T09:50:00',
    scheduledActivationTime: '2026-08-09T10:00:07'
  }), 607000)
  assert.equal(scheduledEntryDelayMs({
    serverTime: '2026-08-09T10:00:08',
    scheduledActivationTime: '2026-08-09T10:00:07'
  }), 0)
  assert.equal(scheduledEntryDelayMs({
    serverTime: '2026-08-09T09:30:00',
    scheduledActivationTime: '2026-08-09T10:00:00'
  }), 30 * 60 * 1000)
})

test('试卷静态题目与独立草稿在前端合并', () => {
  const questions = mergePaperDelivery({
    questions: [
      { questionId: 1, content: '第一题' },
      { questionId: 2, content: '第二题' }
    ],
    draftAnswers: [{ questionId: 2, answerText: 'B' }]
  })

  assert.equal(questions[0].currentAnswer, null)
  assert.equal(questions[1].currentAnswer, 'B')
})
