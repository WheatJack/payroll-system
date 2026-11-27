import { describe, expect, test } from 'vitest'
import { LANDING_ROUTES, resolveLandingPath } from '../landing'

const PAYROLL_CLERK = [
  'payroll:record:view',
  'payroll:record:edit',
  'payroll:period:lock',
  'payroll:import:submit',
]

const SYS_ADMIN = [
  'sys:user:view',
  'sys:user:edit',
  'sys:role:view',
  'sys:role:edit',
  'sys:role:grant:all',
  'sys:dept:edit',
  'sys:company:edit',
]

describe('登录落地页选择', () => {
  test('系统管理员（只有 sys:*）落到系统管理页，而不是工资期间', () => {
    expect(resolveLandingPath(SYS_ADMIN)).toBe('/system/users')
  })

  test('薪酬专员落到工资期间', () => {
    expect(resolveLandingPath(PAYROLL_CLERK)).toBe('/periods')
  })

  test('两类权限都有时薪酬入口优先', () => {
    expect(resolveLandingPath([...SYS_ADMIN, ...PAYROLL_CLERK])).toBe('/periods')
  })

  test('只有部分 sys 权限时落到其能进的第一页', () => {
    expect(resolveLandingPath(['sys:role:view'])).toBe('/system/roles')
    expect(resolveLandingPath(['sys:dept:edit'])).toBe('/system/depts')
    expect(resolveLandingPath(['sys:company:edit'])).toBe('/system/companies')
  })

  test('没有任何页面权限时返回 null（走兜底页，不进 403 死循环）', () => {
    expect(resolveLandingPath([])).toBeNull()
    expect(resolveLandingPath(['payroll:export'])).toBeNull()
    expect(resolveLandingPath(['sys:user:edit'])).toBeNull()
  })

  test('落地清单里的权限与菜单入口保持一致（防止清单漂移）', () => {
    expect(LANDING_ROUTES.map((route) => route.path)).toEqual([
      '/periods',
      '/employees',
      '/reports/monthly',
      '/reports/annual',
      '/system/users',
      '/system/roles',
      '/system/depts',
      '/system/companies',
    ])
  })
})
