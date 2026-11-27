/**
 * 组件冒烟测试的公共工具：
 * - 真实的 Pinia + Vue Router（memory history）+ Element Plus
 * - 用 DEMO_TOKEN / DEMO_USER 预置登录态与全部权限
 * - waitFor 轮询等待异步渲染（vxe-table 等是异步渲染）
 */
import { flushPromises, mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import VXETable from 'vxe-table'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, type Component } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { DEMO_TOKEN, DEMO_USER } from '@/api/mock/fixtures'
import { setToken } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const stub = defineComponent({ template: '<div />' })

const routes = [
  { path: '/login', name: 'login', component: stub },
  { path: '/', name: 'home', component: stub },
  { path: '/employees', name: 'employees', component: stub },
  { path: '/items', name: 'items', component: stub },
  { path: '/periods', name: 'periods', component: stub },
  { path: '/periods/:id/grid', name: 'payroll-grid', component: stub },
  { path: '/periods/:id/import', name: 'period-import', component: stub },
  { path: '/reports/monthly', name: 'report-monthly', component: stub },
  { path: '/reports/annual', name: 'report-annual', component: stub },
  { path: '/system/users', name: 'system-users', component: stub },
  { path: '/system/roles', name: 'system-roles', component: stub },
  { path: '/system/depts', name: 'system-depts', component: stub },
  { path: '/system/companies', name: 'system-companies', component: stub },
  { path: '/system/config', name: 'system-config', component: stub },
  { path: '/403', name: 'forbidden', component: stub },
  { path: '/no-access', name: 'no-access', component: stub },
  { path: '/:pathMatch(.*)*', name: 'fallback', component: stub },
]

export interface MountedPage {
  wrapper: VueWrapper
  router: Router
  auth: ReturnType<typeof useAuthStore>
}

export async function mountPage(component: Component, path = '/'): Promise<MountedPage> {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()

  const auth = useAuthStore()
  // 请求拦截器从 localStorage 取 token，测试里必须同时写入，否则所有需要认证的接口会 401
  setToken(DEMO_TOKEN)
  auth.token = DEMO_TOKEN
  auth.applyUser(DEMO_USER)

  const wrapper = mount(component, {
    // vxe-table 在 main.ts 里是全局注册的，测试必须同样注册，否则编制台渲染不出表格
    global: { plugins: [pinia, router, ElementPlus, VXETable] },
    attachTo: document.body,
  })
  await flushPromises()
  return { wrapper, router, auth }
}

/** 轮询等待条件成立（默认 3s），返回条件值。 */
export async function waitFor<T>(probe: () => T | null | undefined, timeout = 3000, interval = 25): Promise<T> {
  const started = Date.now()
  for (;;) {
    const value = probe()
    if (value) return value
    if (Date.now() - started > timeout) {
      throw new Error(`waitFor 超时（${timeout}ms）`)
    }
    await new Promise((resolve) => setTimeout(resolve, interval))
    await flushPromises()
  }
}

/** 表格数据行（Element Plus）。 */
export function tableRows(wrapper: VueWrapper): DOMWrapper<Element>[] {
  return wrapper.findAll('.el-table__body tbody tr')
}
