import { describe, expect, test } from 'vitest'
import { buildApiError, conflictRecordIds } from '../client'

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

describe('buildApiError 文案选择', () => {
  test('后端 msg 永远优先（删除部门/用户被拒时不会错配成版本冲突）', () => {
    expect(buildApiError(409, '存在下级部门，请先迁移或删除下级', null).message).toBe(
      '存在下级部门，请先迁移或删除下级',
    )
  })

  test('409 缺省文案保持中性，不假定是并发修改', () => {
    const message = buildApiError(409, undefined, null).message
    expect(message).toBe('操作冲突，请刷新后重试')
    expect(message).not.toContain('他人修改')
  })

  test('其余状态码缺省文案', () => {
    expect(buildApiError(403, undefined, null).message).toBe('无权限执行该操作')
    expect(buildApiError(401, undefined, null).message).toBe('未认证或登录已过期')
    expect(buildApiError(400, undefined, null).message).toBe('参数校验失败')
    expect(buildApiError(500, '', null).message).toBe('服务器内部错误')
  })
})
