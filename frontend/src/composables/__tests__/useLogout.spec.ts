import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeAll, describe, expect, test } from 'vitest'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { DEMO_TOKEN, DEMO_USER } from '@/api/mock/fixtures'
import { useAuthStore } from '@/stores/auth'
import { useGridStore } from '@/stores/grid'
import { useLogout } from '../useLogout'

describe('useLogout', () => {
  beforeAll(() => {
    setActivePinia(createPinia())
  })

  test('登出时清空编制台缓存，避免换账号看到上一个人的工资行（C-1）', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/', name: 'home', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()

    const auth = useAuthStore()
    auth.token = DEMO_TOKEN
    auth.applyUser(DEMO_USER)

    const grid = useGridStore()
    grid.load(
      {
        period: { id: '3', periodYm: '2026-11', status: 'EDITING' },
        columns: [],
        rows: [
          {
            recordId: '101',
            version: 0,
            employeeId: '1',
            empNo: 'E001',
            empName: '张三',
            deptId: '1',
            deptName: '北京研发部',
            employeeType: 'REGULAR',
            jobLevel: 'P6',
            seniorityYears: '6.00',
            grossAmount: '1.00',
            deductionAmount: '0.00',
            netAmount: '1.00',
            items: { PERF_BONUS: '1.00' },
          },
        ],
        total: 1,
      },
      DEMO_USER.userId,
    )
    grid.edit('101', 'PERF_BONUS', '2.00')
    expect(grid.dirtyCount).toBe(1)

    let logout!: () => Promise<void>
    const Harness = defineComponent({
      setup() {
        logout = useLogout().logout
        return () => h('div')
      },
    })
    mount(Harness, { global: { plugins: [pinia, router] } })

    await logout()

    expect(grid.rows).toHaveLength(0)
    expect(grid.dirtyCount).toBe(0)
    expect(grid.ownerUserId).toBe('')
  })

  test('退出登录清 token 并跳转到 login（不能停在原页面）', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/', name: 'home', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()

    const auth = useAuthStore()
    auth.token = DEMO_TOKEN
    auth.applyUser(DEMO_USER)
    expect(auth.permissions.length).toBeGreaterThan(0)

    let logout!: () => Promise<void>
    const Harness = defineComponent({
      setup() {
        logout = useLogout().logout
        return () => h('div')
      },
    })
    mount(Harness, { global: { plugins: [pinia, router] } })

    expect(router.currentRoute.value.path).toBe('/')
    await logout()

    expect(router.currentRoute.value.name).toBe('login')
    expect(auth.token).toBe('')
    expect(auth.permissions).toEqual([])
  })
})
