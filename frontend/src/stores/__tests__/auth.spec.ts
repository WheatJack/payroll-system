import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { useAuthStore } from '../auth'
import { TOKEN_KEY } from '@/api/client'

vi.mock('@/api/auth', () => ({
  login: vi.fn(async () => ({
    token: 'signed-token',
    user: {
      userId: '1',
      username: 'hr',
      realName: '人事专员',
      companyId: '1',
      employeeId: null,
      dataScope: 'COMPANY',
      permissions: ['payroll:record:view', 'payroll:record:edit'],
    },
  })),
  fetchCurrentUser: vi.fn(async () => ({
    userId: '1',
    username: 'hr',
    realName: '人事专员',
    companyId: '1',
    employeeId: null,
    dataScope: 'COMPANY',
    permissions: ['payroll:record:view', 'payroll:record:edit', 'payroll:period:lock'],
  })),
}))

describe('auth store 权限点', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  test('没有权限点时 has 返回 false', () => {
    const store = useAuthStore()
    store.permissions = ['payroll:record:view']
    expect(store.has('payroll:period:lock')).toBe(false)
    expect(store.has('payroll:record:view')).toBe(true)
  })

  test('登录写入 token 与权限点，并持久化 token', async () => {
    const store = useAuthStore()
    await store.login('hr', 'secret')
    expect(store.token).toBe('signed-token')
    expect(localStorage.getItem(TOKEN_KEY)).toBe('signed-token')
    expect(store.has('payroll:record:edit')).toBe(true)
    expect(store.companyId).toBe('1')
  })

  test('fetchMe 以服务端权限点为准', async () => {
    const store = useAuthStore()
    await store.fetchMe()
    expect(store.has('payroll:period:lock')).toBe(true)
    expect(store.has('payroll:period:unlock')).toBe(false)
  })

  test('登出清空 token、用户与权限', () => {
    const store = useAuthStore()
    store.token = 'x'
    store.permissions = ['payroll:record:view']
    store.logout()
    expect(store.token).toBe('')
    expect(store.permissions).toEqual([])
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
  })
})
