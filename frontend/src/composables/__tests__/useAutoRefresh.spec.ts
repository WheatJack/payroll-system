import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { useAutoRefresh } from '../useAutoRefresh'

function mountHarness(refresh: () => void, options?: Parameters<typeof useAutoRefresh>[1]) {
  const Harness = defineComponent({
    setup() {
      useAutoRefresh(refresh, options)
      return () => h('div')
    },
  })
  return mount(Harness)
}

describe('useAutoRefresh（期间列表静默刷新）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('每 15 秒静默刷新一次', async () => {
    const refresh = vi.fn()
    const wrapper = mountHarness(refresh)

    // advanceTimersByTimeAsync 会顺带冲刷微任务，避免 running 守卫挡住第二次
    await vi.advanceTimersByTimeAsync(14_999)
    expect(refresh).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(refresh).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(15_000)
    expect(refresh).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  test('窗口重新获得焦点时立即刷新', async () => {
    const refresh = vi.fn()
    const wrapper = mountHarness(refresh)

    window.dispatchEvent(new Event('focus'))
    expect(refresh).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  test('标签页重新可见时刷新，隐藏时不刷新', async () => {
    const refresh = vi.fn()
    const wrapper = mountHarness(refresh)

    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    expect(refresh).not.toHaveBeenCalled()

    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    expect(refresh).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  test('enabled=false 时跳过（例如正在编辑）', async () => {
    const refresh = vi.fn()
    const wrapper = mountHarness(refresh, { enabled: () => false })

    await vi.advanceTimersByTimeAsync(15_000)
    window.dispatchEvent(new Event('focus'))
    expect(refresh).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  test('卸载后不再刷新（无定时器泄漏）', async () => {
    const refresh = vi.fn()
    const wrapper = mountHarness(refresh)
    wrapper.unmount()

    await vi.advanceTimersByTimeAsync(60_000)
    window.dispatchEvent(new Event('focus'))
    expect(refresh).not.toHaveBeenCalled()
  })
})
