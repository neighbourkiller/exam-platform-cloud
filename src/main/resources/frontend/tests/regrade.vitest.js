import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { useRegrade } from '../src/composables/useRegrade'
import * as api from '../src/api'
vi.mock('../src/api', () => ({
  createRegradeApi: vi.fn(), regradeDetailApi: vi.fn(), regradeHistoryApi: vi.fn(), regradeKeyApi: vi.fn(),
  restoreRegradeApi: vi.fn(), retryRegradeApi: vi.fn(), regradeBankContextApi: vi.fn(), retryRegradeBankApi: vi.fn()
}))
let state, wrapper
const open = () => {
  wrapper = mount(defineComponent({ setup() { state = useRegrade(computed(() => '10')); return {} }, template: '<div />' }))
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  api.regradeKeyApi.mockResolvedValue({ version: 0, activeJob: false, accepting: true, answers: { '1': 'A' }, snapshot: { questions: [] } })
  api.regradeHistoryApi.mockResolvedValue([])
  api.regradeDetailApi.mockResolvedValue({ id: '90071992547409999', status: 'RUNNING' })
})
afterEach(() => { wrapper?.unmount(); vi.useRealTimers() })
describe('重判交互状态', () => {
  it('网络重试保持相同幂等键并保留大整数任务编号', async () => {
    open(); await flushPromises()
    api.createRegradeApi.mockRejectedValueOnce(new Error('网络失败')).mockResolvedValueOnce({ jobId: '90071992547409999' })
    await state.submit({ '1': 'B' }, '答案录错')
    expect(state.error.value).toBe('网络失败')
    await state.submit({ '1': 'B' }, '答案录错')
    expect(api.createRegradeApi.mock.calls[0][1].requestKey).toBe(api.createRegradeApi.mock.calls[1][1].requestKey)
    expect(api.regradeDetailApi).toHaveBeenCalledWith('10', '90071992547409999', 1)
  })
  it('恢复版本使用恢复接口并发送空纠错集合', async () => {
    open(); await flushPromises()
    api.restoreRegradeApi.mockResolvedValue({ jobId: '42' })
    await state.submit({}, '恢复原始答案', 0)
    expect(api.createRegradeApi).not.toHaveBeenCalled()
    expect(api.restoreRegradeApi).toHaveBeenCalledWith('10', 0, expect.objectContaining({ answers: {}, reason: '恢复原始答案' }))
  })
  it('关闭面板停止轮询且忽略迟到结果', async () => {
    open(); await flushPromises()
    const calls = api.regradeKeyApi.mock.calls.length
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(20000)
    expect(api.regradeKeyApi).toHaveBeenCalledTimes(calls)
  })
  it('后端活动任务状态与当前历史分页无关', async () => {
    api.regradeKeyApi.mockResolvedValue({ version: 3, activeJob: true, accepting: true })
    open(); await flushPromises()
    expect(state.history.value).toEqual([])
    expect(state.unfinished.value).toBe(true)
  })
})
