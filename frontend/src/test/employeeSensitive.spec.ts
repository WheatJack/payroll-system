/**
 * 证件号按需查看：权限门槛 + 二次确认 + 审计提示 + 关闭后不残留明文。
 */
import { setupServer } from 'msw/node'
import { HttpResponse, http } from 'msw'
import { ElMessageBox } from 'element-plus'
import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { handlers } from '@/api/mock/handlers'
import { DEMO_USER } from '@/api/mock/fixtures'
import EmployeeListView from '@/views/employee/EmployeeListView.vue'
import { mountPage, tableRows, waitFor } from '@/test/pageTestUtils'

const server = setupServer(...handlers)
const mounted: Array<{ wrapper: { unmount: () => void } }> = []

let sensitiveCalls = 0

async function mountEmployees(authUser = DEMO_USER) {
  server.use(
    http.get('/api/v1/employees/:id/sensitive', () => {
      sensitiveCalls += 1
      return HttpResponse.json({
        code: 0,
        msg: 'ok',
        data: { idNo: '110101199001011234', mobile: '13800008000' },
      })
    }),
  )
  const page = await mountPage(EmployeeListView, '/employees', { authUser })
  mounted.push(page)
  await waitFor(() => (tableRows(page.wrapper).length > 0 ? true : null))
  return page
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  while (mounted.length > 0) mounted.pop()?.wrapper.unmount()
  server.resetHandlers()
  sensitiveCalls = 0
  vi.restoreAllMocks()
})
afterAll(() => server.close())

describe('证件号按需查看', () => {
  test('点击前必须二次确认，确认后显示明文并提示已记入审计', async () => {
    const { wrapper } = await mountEmployees()
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValueOnce('confirm' as never)

    await wrapper.find('.sensitive-eye').trigger('click')
    await waitFor(() => (document.body.textContent?.includes('110101199001011234') ? true : null))

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(String(confirm.mock.calls[0][0])).toContain('会记录审计日志')
    expect(sensitiveCalls).toBe(1)
    expect(document.body.textContent).toContain('本次查看已记入审计')
    expect(document.body.textContent).toContain('13800008000')
  })

  test('取消二次确认时不请求明文接口', async () => {
    const { wrapper } = await mountEmployees()
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce(new Error('cancel'))

    await wrapper.find('.sensitive-eye').trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 200))

    expect(sensitiveCalls).toBe(0)
    expect(document.body.textContent).not.toContain('110101199001011234')
  })

  test('明文只存在于组件局部状态，不进入任何 store（关闭即丢）', async () => {
    const { wrapper, pinia } = await mountEmployees()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValueOnce('confirm' as never)

    await wrapper.find('.sensitive-eye').trigger('click')
    await waitFor(() => (document.body.textContent?.includes('110101199001011234') ? true : null))

    // 需求：明文不得留在任何状态里——Pinia 全量 state 序列化中不能出现
    const serialized = JSON.stringify(pinia.state.value)
    expect(serialized).not.toContain('110101199001011234')
    expect(serialized).not.toContain('13800008000')
  })

  test('没有 payroll:sensitive:view 时不显示查看图标', async () => {
    const noSensitive = {
      ...DEMO_USER,
      permissions: DEMO_USER.permissions.filter((permission) => permission !== 'payroll:sensitive:view'),
    }
    const { wrapper } = await mountEmployees(noSensitive)

    expect(wrapper.findAll('.sensitive-eye')).toHaveLength(0)
  })
})
