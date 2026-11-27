import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { debounce } from '../debounce'

describe('debounce', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  test('连续输入只执行最后一次，且带最后一次参数', () => {
    const fn = vi.fn()
    const debounced = debounce(fn, 150)

    debounced('a')
    vi.advanceTimersByTime(100)
    debounced('b')
    debounced('c')
    expect(fn).not.toHaveBeenCalled()

    vi.advanceTimersByTime(150)
    expect(fn).toHaveBeenCalledTimes(1)
    expect(fn).toHaveBeenCalledWith('c')
  })

  test('cancel 后不执行', () => {
    const fn = vi.fn()
    const debounced = debounce(fn, 150)
    debounced()
    debounced.cancel()
    vi.advanceTimersByTime(500)
    expect(fn).not.toHaveBeenCalled()
  })

  test('flush 立即执行挂起的那一次', () => {
    const fn = vi.fn()
    const debounced = debounce(fn, 150)
    debounced('now')
    debounced.flush()
    expect(fn).toHaveBeenCalledTimes(1)
    expect(fn).toHaveBeenCalledWith('now')
    vi.advanceTimersByTime(500)
    expect(fn).toHaveBeenCalledTimes(1)
  })
})
