import { beforeEach, describe, expect, it, vi } from 'vitest'
import { indexedDB, IDBKeyRange } from 'fake-indexeddb'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { calculateServerOffset, responseDeadlineEpochMs, useServerClock } from '../src/composables/useServerClock'
import { useExamSnapshotSync } from '../src/composables/useExamSnapshotSync'
import { createSubmissionPlan, isRuntimeFinalized } from '../src/utils/submissionFlow'
import {
  loadDraft,
  loadSubmissionEvidence,
  purgeExpiredSubmissionEvidence,
  saveDraft,
  saveSubmissionEvidence
} from '../src/utils/examDraftStore'

const SubmissionStatusHarness = defineComponent({
  props: { result: { type: Object, required: true } },
  setup: (props) => () => h(
    'div',
    isRuntimeFinalized(props.result)
      ? 'FINALIZED'
      : String(props.result?.status || '').toUpperCase() === 'FAILED' ? 'FAILED' : 'SUBMITTING'
  )
})

describe('服务器校时时钟', () => {
  it('使用请求中点抵消 RTT 并统一按 epoch 判断截止', () => {
    expect(calculateServerOffset({ sentAtMs: 1_000, receivedAtMs: 1_400, serverEpochMs: 2_200 }))
      .toEqual({ offsetMs: 1_000, uncertaintyMs: 200 })
    const clock = useServerClock(() => 10_000)
    clock.observe({ serverEpochMs: 16_000 }, 9_000, 11_000)
    expect(clock.nowMs()).toBe(16_000)
    expect(clock.secondsUntil(18_500)).toBe(2)
    expect(clock.isExpired(15_999)).toBe(true)
  })

  it('优先使用无时区歧义的服务端截止字段', () => {
    expect(responseDeadlineEpochMs({ deadlineEpochMs: 123_456, deadlineTime: '2099-01-01' }))
      .toBe(123_456)
  })

  it('浏览器快五分钟仍由服务器样本校正截止判断', () => {
    const browserNow = 1_300_000
    const clock = useServerClock(() => browserNow)
    clock.observe({ serverEpochMs: 1_000_000 }, browserNow - 200, browserNow + 200)
    expect(clock.nowMs()).toBe(1_000_000)
    expect(clock.isExpired(1_000_001)).toBe(false)
  })
})

describe('快照与交卷状态', () => {
  it('旧响应不能覆盖更高服务端版本', () => {
    const { syncState, observeAck, nextClientSequence } = useExamSnapshotSync()
    expect(observeAck({ accepted: true, serverRevision: 8, storedClientSequence: 80 })).toBe(true)
    expect(observeAck({ accepted: true, serverRevision: 7, storedClientSequence: 70 })).toBe(true)
    expect(syncState.serverRevision).toBe(8)
    expect(syncState.snapshotVersion).toBe(80)
    expect(nextClientSequence()).toBe(81)
    expect(observeAck({ accepted: false, serverRevision: 9, storedClientSequence: 90 })).toBe(false)
    expect(syncState.serverRevision).toBe(9)
    expect(syncState.snapshotVersion).toBe(80)
  })

  it('组件状态覆盖 SUBMITTING 到 FINALIZED 与 FAILED', async () => {
    const wrapper = mount(SubmissionStatusHarness, {
      props: { result: { phase: 'HANDOFF_ACCEPTED', status: 'SUBMITTING' } }
    })
    expect(wrapper.text()).toBe('SUBMITTING')
    await wrapper.setProps({ result: { phase: 'RUNTIME_FINALIZED', finalSnapshotVersion: 80 } })
    expect(wrapper.text()).toBe('FINALIZED')
    await wrapper.setProps({ result: { status: 'FAILED', failureCode: 'DEPENDENCY_TIMEOUT' } })
    expect(wrapper.text()).toBe('FAILED')
  })

  it('离线跨截止不会把本地队列静默视为成功', () => {
    expect(createSubmissionPlan({ online: false, deadlineReached: true }).mode)
      .toBe('OFFLINE_DEADLINE_WAIT')
  })

  it('ACK 只确认对应编辑版本，较新的本地编辑仍保持 dirty', () => {
    const { syncState, markEdited, observeAck } = useExamSnapshotSync()
    const firstEdit = markEdited()
    expect(observeAck({ accepted: true, storedClientSequence: 1 }, 1, firstEdit)).toBe(true)
    expect(syncState.dirty).toBe(false)

    const secondEdit = markEdited()
    expect(secondEdit).toBe(firstEdit + 1)
    expect(observeAck({ accepted: true, storedClientSequence: 2 }, 2, firstEdit)).toBe(true)
    expect(syncState.confirmedEditVersion).toBe(firstEdit)
    expect(syncState.dirty).toBe(true)
  })

  it('完整快照请求只允许一个在途，并在完成后发送最新待发送版本', async () => {
    const { setSnapshotSender, enqueueSnapshot } = useExamSnapshotSync()
    const pending = []
    const sender = vi.fn((item) => new Promise((resolve) => {
      pending.push({ item, resolve })
    }))
    setSnapshotSender(sender)

    const first = enqueueSnapshot({ clientSequence: 1 })
    const second = enqueueSnapshot({ clientSequence: 2 })
    await Promise.resolve()
    expect(sender).toHaveBeenCalledTimes(1)
    expect(sender).toHaveBeenLastCalledWith({ clientSequence: 1 })

    pending[0].resolve({ accepted: true })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(sender).toHaveBeenCalledTimes(2)
    expect(sender).toHaveBeenLastCalledWith({ clientSequence: 2 })

    pending[1].resolve({ accepted: true })
    await Promise.all([first, second])
  })

  it('持续编辑时仍在最大等待时间触发一次快照调度', async () => {
    vi.useFakeTimers()
    try {
      const { setSnapshotSender, scheduleSnapshot } = useExamSnapshotSync()
      const sender = vi.fn().mockResolvedValue({ accepted: true })
      setSnapshotSender(sender)
      const factory = vi.fn(() => ({ clientSequence: sender.mock.calls.length + 1 }))

      scheduleSnapshot(factory, { debounceMs: 500, maxWaitMs: 2_000 })
      vi.advanceTimersByTime(400)
      scheduleSnapshot(factory, { debounceMs: 500, maxWaitMs: 2_000 })
      vi.advanceTimersByTime(400)
      scheduleSnapshot(factory, { debounceMs: 500, maxWaitMs: 2_000 })
      vi.advanceTimersByTime(1_200)
      await vi.runOnlyPendingTimersAsync()

      expect(factory).toHaveBeenCalledTimes(1)
      expect(sender).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('本地申诉证据保留', () => {
  beforeEach(() => {
    vi.stubGlobal('indexedDB', indexedDB)
    vi.stubGlobal('IDBKeyRange', IDBKeyRange)
    Object.defineProperty(window, 'indexedDB', { configurable: true, value: indexedDB })
  })

  it('仅清理已超过七天保留期的记录', async () => {
    await saveSubmissionEvidence({
      userId: 'u1', examId: 'expired', answers: { 1: 'A' }, expiresAt: 1_000
    })
    await saveSubmissionEvidence({
      userId: 'u1', examId: 'active', answers: { 1: 'B' }, expiresAt: 2_000
    })
    expect(await purgeExpiredSubmissionEvidence(1_500)).toBe(1)
    expect(await purgeExpiredSubmissionEvidence(1_500)).toBe(0)
    expect(await loadSubmissionEvidence('u1', 'expired')).toBeNull()
    expect(await loadSubmissionEvidence('u1', 'active')).toMatchObject({ answers: { 1: 'B' } })
  })

  it('刷新后恢复服务端版本和待交卷意图', async () => {
    await saveDraft({
      userId: 'u2', examId: 'refresh', answers: { 1: 'A' }, markedQuestionIds: [],
      snapshotVersion: 7, clientSequence: 8, serverRevision: 3,
      pendingSubmitIntent: { attemptedAt: 1_000 }, dirty: true,
      editVersion: 9, confirmedEditVersion: 8
    })
    const restored = await loadDraft('u2', 'refresh')
    expect(restored).toMatchObject({
      snapshotVersion: 7,
      clientSequence: 8,
      serverRevision: 3,
      dirty: true,
      editVersion: 9,
      confirmedEditVersion: 8,
      pendingSubmitIntent: { attemptedAt: 1_000 }
    })
  })
})
