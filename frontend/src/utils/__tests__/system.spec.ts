import { describe, expect, test } from 'vitest'
import type { PermissionItem } from '@/api/models'
import { dataScopeLabel, groupPermissions, requiresGrantAll, validatePassword } from '../system'

describe('权限点分组（目录来自接口，不硬编码）', () => {
  const items: PermissionItem[] = [
    { code: 'payroll:record:view', name: '查看核算', module: 'PAYROLL' },
    { code: 'payroll:export', name: '导出', module: 'PAYROLL' },
    { code: 'sys:user:view', name: '查看用户', module: 'SYS' },
  ]

  test('按模块分组且顺序稳定（PAYROLL 在 SYS 前）', () => {
    const groups = groupPermissions(items)
    expect(groups.map((group) => group.module)).toEqual(['PAYROLL', 'SYS'])
    expect(groups[0].label).toBe('核算与薪资')
    expect(groups[0].items).toHaveLength(2)
    expect(groups[1].items[0].code).toBe('sys:user:view')
  })

  test('空模块不产生空分组', () => {
    const groups = groupPermissions(items.filter((item) => item.module === 'SYS'))
    expect(groups).toHaveLength(1)
    expect(groups[0].module).toBe('SYS')
  })

  test('空目录返回空数组', () => {
    expect(groupPermissions([])).toEqual([])
  })
})

describe('数据范围与密码校验', () => {
  test('数据范围文案', () => {
    expect(dataScopeLabel('ALL')).toBe('集团全部主体')
    expect(dataScopeLabel('DEPT_SUB')).toBe('本部门及下级')
    expect(dataScopeLabel(undefined)).toBe('—')
  })

  test('授予 ALL 需要 sys:role:grant:all 提示', () => {
    expect(requiresGrantAll('ALL')).toBe(true)
    expect(requiresGrantAll('COMPANY')).toBe(false)
  })

  test('密码至少 8 位，过长也拒绝', () => {
    expect(validatePassword('')).toBe('请输入密码')
    expect(validatePassword('1234567')).toBe('密码至少 8 位')
    expect(validatePassword('12345678')).toBeNull()
    expect(validatePassword('a'.repeat(129))).toBe('密码不能超过 128 位')
  })
})
