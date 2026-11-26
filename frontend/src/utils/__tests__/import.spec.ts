import { describe, expect, test } from 'vitest'
import {
  batchSummary,
  canCommit,
  commitBlockedReason,
  isReadyToCommit,
  isTerminalBatchStatus,
  pollExhausted,
  shouldKeepPolling,
} from '../import'

function batch(overrides: Partial<Parameters<typeof commitBlockedReason>[0]> = {}) {
  return {
    batchId: '1',
    status: 'VALIDATED' as const,
    totalRows: 10,
    successRows: 10,
    failRows: 0,
    errors: [],
    ...overrides,
  }
}

describe('导入批次能否提交', () => {
  test('全部成功且已预校验 → 可提交', () => {
    expect(canCommit(batch(), true)).toBe(true)
    expect(commitBlockedReason(batch(), true)).toBeNull()
    expect(isReadyToCommit(batch())).toBe(true)
  })

  test('failRows > 0 → 禁止提交，并说明失败行数（前后端双重保护）', () => {
    const withErrors = batch({ totalRows: 10, successRows: 7, failRows: 3 })
    expect(canCommit(withErrors, true)).toBe(false)
    expect(commitBlockedReason(withErrors, true)).toContain('3 行失败')
    expect(commitBlockedReason(withErrors, true)).toContain('成功 7 行')
    expect(isReadyToCommit(withErrors)).toBe(false)
  })

  test('已提交 / 校验中 / 校验失败 / 未校验 都不能提交', () => {
    expect(commitBlockedReason(batch({ status: 'COMMITTED' }), true)).toContain('已提交')
    expect(commitBlockedReason(batch({ status: 'VALIDATING' }), true)).toContain('进行中')
    expect(commitBlockedReason(batch({ status: 'FAILED' }), true)).toContain('重新上传')
    expect(commitBlockedReason(batch({ status: undefined as never }), true)).toContain('预校验')
  })

  test('没有提交权限时直接拦截', () => {
    expect(canCommit(batch(), false)).toBe(false)
    expect(commitBlockedReason(batch(), false)).toContain('payroll:import:submit')
  })

  test('没有批次时提示先上传', () => {
    expect(commitBlockedReason(null, true)).toContain('请先上传')
    expect(canCommit(null, true)).toBe(false)
  })

  test('批次摘要文案包含总数与成功/失败', () => {
    expect(batchSummary(batch({ totalRows: 12, successRows: 10, failRows: 2 }))).toBe(
      '共 12 行：成功 10 行，失败 2 行',
    )
  })
})

describe('异步预校验轮询', () => {
  test('VALIDATING 需要继续轮询，其余终态停止', () => {
    expect(shouldKeepPolling('VALIDATING')).toBe(true)
    expect(shouldKeepPolling('VALIDATED')).toBe(false)
    expect(shouldKeepPolling('FAILED')).toBe(false)
    expect(shouldKeepPolling('COMMITTED')).toBe(false)
  })

  test('终态判定包含 VALIDATED / FAILED / COMMITTED', () => {
    expect(isTerminalBatchStatus('VALIDATED')).toBe(true)
    expect(isTerminalBatchStatus('FAILED')).toBe(true)
    expect(isTerminalBatchStatus('COMMITTED')).toBe(true)
    expect(isTerminalBatchStatus('VALIDATING')).toBe(false)
  })

  test('轮询次数耗尽判定', () => {
    expect(pollExhausted(0)).toBe(false)
    expect(pollExhausted(59)).toBe(false)
    expect(pollExhausted(60)).toBe(true)
    expect(pollExhausted(3, 3)).toBe(true)
  })
})
