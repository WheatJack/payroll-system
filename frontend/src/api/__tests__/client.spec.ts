import { describe, expect, test } from 'vitest'
import { conflictRecordIds } from '../client'

function fakeApiError(code: number, data: unknown): Error {
  const error = new Error('fake') as Error & { code: number; data: unknown }
  error.code = code
  error.data = data
  return error
}

describe('conflictRecordIds（R8：409 冲突行提取）', () => {
  test('409 且带 conflicts 时返回全部 recordId', () => {
    expect(
      conflictRecordIds(fakeApiError(409, { conflicts: [{ recordId: '101' }, { recordId: '102' }] })),
    ).toEqual(['101', '102'])
  })

  test('409 但没有 conflicts（如期间已锁定）返回空数组', () => {
    expect(conflictRecordIds(fakeApiError(409, null))).toEqual([])
  })

  test('非 409 不当作冲突', () => {
    expect(conflictRecordIds(fakeApiError(400, { conflicts: [{ recordId: '101' }] }))).toEqual([])
    expect(conflictRecordIds(new Error('network'))).toEqual([])
  })
})
