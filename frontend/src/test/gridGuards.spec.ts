/**
 * I-1：编制台未保存修改在「路由离开 / 关闭页面」时必须被提示，不能静默丢失。
 * 这里用真实 router-view 挂载（onBeforeRouteLeave 需要路由上下文）。
 */
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { ElMessageBox } from 'element-plus'
import VXETable from 'vxe-table'
import { setupServer } from 'msw/node'
import { HttpResponse, http } from 'msw'
import { afterAll, beforeAll, beforeEach, describe, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { RouterView, createMemoryHistory, createRouter } from 'vue-router'
import { handlers } from '@/api/mock/handlers'
import { DEMO_TOKEN, DEMO_USER } from '@/api/mock/fixtures'
import { setToken } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useGridStore } from '@/stores/grid'
import PayrollGridView from '@/views/period/PayrollGridView.vue'
import { waitFor } from '@/test/pageTestUtils'

const server = setupServer(...handlers)
const Empty = defineComponent({ setup: () => () => h('div') })

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterAll(() => server.close())

async function mountGridInRouter() {
  const pinia = createPinia()
  setActivePinia(pinia)
  setToken(DEMO_TOKEN)
  const auth = useAuthStore()
  auth.token = DEMO_TOKEN
  auth.applyUser(DEMO_USER)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/periods/:id/grid', name: 'payroll-grid', component: PayrollGridView },
      { path: '/periods', name: 'periods', component: Empty },
    ],
  })
  await router.push('/periods/3/grid')
  await router.isReady()

  const RouterHost = defineComponent({ setup: () => () => h(RouterView) })
  const wrapper = mount(RouterHost, {
    global: { plugins: [pinia, router, ElementPlus, VXETable] },
    attachTo: document.body,
  })
  await flushPromises()
  const store = useGridStore()
  await waitFor(() => (store.rows.length > 0 ? true : null))
  store.edit('101', 'PERF_BONUS', '999.00')
  await flushPromises()
  return { wrapper, router, store }
}

describe('编制台未保存修改的离开保护（I-1）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  test('有未保存修改时路由离开被拦住（用户选择继续编辑）', async () => {
    const { wrapper, router } = await mountGridInRouter()
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce(new Error('cancel'))

    await router.push('/periods')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/periods/3/grid')
    wrapper.unmount()
  })

  test('用户确认离开后正常跳转', async () => {
    const { wrapper, router } = await mountGridInRouter()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValueOnce('confirm' as never)

    await router.push('/periods')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/periods')
    wrapper.unmount()
  })

  test('关闭/刷新页面前提示：dirty 时 preventDefault，清空后不再拦截', async () => {
    const { wrapper, store } = await mountGridInRouter()

    const dirtyEvent = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirtyEvent)
    expect(dirtyEvent.defaultPrevented).toBe(true)

    store.revertAll()
    const cleanEvent = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(cleanEvent)
    expect(cleanEvent.defaultPrevented).toBe(false)
    wrapper.unmount()
  })
})

// 让 sa 的 http 引用不被 tree-shake 掉（本文件未直接使用，保留导入以便后续扩展）
void http
void HttpResponse
