import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import GradingLease from '../src/components/grading/GradingLease.vue'
import { gradingClaimApi, gradingRenewApi, gradingReleaseApi } from '../src/api'

vi.mock('../src/api', () => ({
  gradingClaimApi: vi.fn(), gradingRenewApi: vi.fn(), gradingReleaseApi: vi.fn()
}))

let wrapper
const open = () => {
  wrapper = mount(GradingLease, {
    props: { answerId: '101' },
    global: { stubs: { ElButton: { template: '<button><slot /></button>' } } }
  })
  return wrapper
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  gradingClaimApi.mockResolvedValue({ token: 'lease-1', expiresInSeconds: 300 })
  gradingRenewApi.mockResolvedValue({ token: 'lease-1', expiresInSeconds: 300 })
  gradingReleaseApi.mockResolvedValue(null)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.useRealTimers()
})

describe('阅卷认领', () => {
  it('认领成功后可批阅，五分钟过期后撤销令牌', async () => {
    open()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('change').at(-1)).toEqual(['lease-1'])
    await vi.advanceTimersByTimeAsync(300_000)
    expect(wrapper.emitted('change').at(-1)).toEqual([null])
    expect(wrapper.text()).toContain('租约已过期')
  })

  it('抢占失败不开放评分', async () => {
    gradingClaimApi.mockRejectedValue(new Error('conflict'))
    open()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('change')).toBeUndefined()
    expect(wrapper.text()).toContain('认领或续期失败')
  })

  it('续期延长有效期，离开页面释放持有的令牌', async () => {
    open()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(240_000)
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(gradingRenewApi).toHaveBeenCalledWith('101', 'lease-1')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.emitted('change').at(-1)).toEqual(['lease-1'])
    wrapper.unmount()
    wrapper = null
    expect(gradingReleaseApi).toHaveBeenCalledWith('101', 'lease-1')
  })

  it('离开页面后才收到认领响应，也释放租约', async () => {
    let resolve
    gradingClaimApi.mockReturnValue(new Promise((done) => { resolve = done }))
    open()
    await wrapper.find('button').trigger('click')
    wrapper.unmount()
    wrapper = null
    resolve({ token: 'late', expiresInSeconds: 300 })
    await flushPromises()
    expect(gradingReleaseApi).toHaveBeenCalledWith('101', 'late')
  })
})
