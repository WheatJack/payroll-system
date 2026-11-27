import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeAll, describe, expect, test } from 'vitest'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { DEMO_TOKEN, DEMO_USER } from '@/api/mock/fixtures'
import { useAuthStore } from '@/stores/auth'
import { useLogout } from '../useLogout'

describe('useLogout', () => {
  beforeAll(() => {
    setActivePinia(createPinia())
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
