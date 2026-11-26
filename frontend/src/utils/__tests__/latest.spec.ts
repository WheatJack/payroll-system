import { describe, expect, test } from 'vitest'
import { createLatestOnly } from '../latest'

describe('createLatestOnly（报表查询防竞态）', () => {
  test('只有最后一次请求的令牌是最新的', () => {
    const guard = createLatestOnly()
    const first = guard.next()
    const second = guard.next()
    expect(guard.isLatest(first)).toBe(false)
    expect(guard.isLatest(second)).toBe(true)
  })

  test('模拟「旧响应后到」：旧令牌不通过，新令牌通过', () => {
    const guard = createLatestOnly()
    const stale = guard.next() // 先发的请求
    const fresh = guard.next() // 后发的请求
    // 后发的先返回
    expect(guard.isLatest(fresh)).toBe(true)
    // 先发的后返回 —— 必须被丢弃
    expect(guard.isLatest(stale)).toBe(false)
  })

  test('invalidate 让所有在途请求作废（例如切维度时）', () => {
    const guard = createLatestOnly()
    const token = guard.next()
    guard.invalidate()
    expect(guard.isLatest(token)).toBe(false)
    expect(guard.isLatest(guard.next())).toBe(true)
  })
})
