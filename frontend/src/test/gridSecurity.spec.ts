/**
 * 编制台的两个 Critical 回归：
 * - C-1 跨账号残留：A 加载并编辑后换 B，必须重新拉取且不残留 A 的工资行/dirty
 * - C-2 期间切换竞态：A 期间的慢响应不能覆盖 B 期间的数据
 */
import { HttpResponse, http, delay } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, test } from 'vitest'
import { handlers } from '@/api/mock/handlers'
import { DEMO_USER } from '@/api/mock/fixtures'
import { useGridStore } from '@/stores/grid'
import PayrollGridView from '@/views/period/PayrollGridView.vue'
import { mountPage, waitFor } from '@/test/pageTestUtils'

const server = setupServer(...handlers)
const mounted: Array<{ wrapper: { unmount: () => void } }> = []

function gridPayload(periodId: string, periodYm: string, recordId: string) {
  return {
    period: { id: periodId, periodYm, status: 'EDITING' },
    columns: [
      {
        itemCode: 'PERF_BONUS',
        itemName: '绩效奖金',
        category: 'VARIABLE',
        direction: 1,
        scale: 2,
        editable: true,
      },
    ],
    rows: [
      {
        recordId,
        version: 0,
        employeeId: '1',
        empNo: recordId,
        empName: recordId,
        deptId: '1',
        deptName: '北京研发部',
        employeeType: 'REGULAR',
        jobLevel: 'P6',
        seniorityYears: '6.00',
        grossAmount: '100.00',
        deductionAmount: '0.00',
        netAmount: '100.00',
        items: { PERF_BONUS: '100.00' },
      },
    ],
    total: 1,
  }
}

async function mountGrid(path: string, options?: Parameters<typeof mountPage>[2]) {
  const page = await mountPage(PayrollGridView, path, options)
  mounted.push(page)
  return page
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  while (mounted.length > 0) mounted.pop()?.wrapper.unmount()
  server.resetHandlers()
})
afterAll(() => server.close())

describe('编制台安全与竞态', () => {
  test('C-1 跨账号：A 加载并编辑后换 B，进入同一期间必须重新加载且不残留 A 的数据', async () => {
    const first = await mountGrid('/periods/3/grid')
    const store = useGridStore()
    await waitFor(() => (store.rows.length > 0 ? true : null))

    store.edit('101', 'PERF_BONUS', '999.00')
    expect(store.dirtyCount).toBe(1)
    expect(store.belongsTo(DEMO_USER.userId)).toBe(true)
    first.wrapper.unmount()
    mounted.length = 0

    // 同一 pinia/浏览器会话里换成另一个账号
    const otherUser = { ...DEMO_USER, userId: '99', username: 'other', realName: '另一人' }
    const second = await mountGrid('/periods/3/grid', { pinia: first.pinia, authUser: otherUser })
    await waitFor(() => (store.belongsTo('99') && store.rows.length > 0 ? true : null))

    expect(store.ownerUserId).toBe('99')
    expect(store.dirtyCount).toBe(0) // A 的未保存修改不能留给 B
    expect(store.rows.every((row) => row.recordId !== '999')).toBe(true)
    second.wrapper.unmount()
  })

  test('C-2 期间切换：A 期间的慢响应后到，不能覆盖 B 期间', async () => {
    server.use(
      http.get('/api/v1/periods/3/grid', async () => {
        await delay(500)
        return HttpResponse.json({ code: 0, msg: 'ok', data: gridPayload('3', '2026-11', 'RA-3') })
      }),
      http.get('/api/v1/periods/2/grid', () =>
        HttpResponse.json({ code: 0, msg: 'ok', data: gridPayload('2', '2026-10', 'RB-2') }),
      ),
    )

    const { router } = await mountGrid('/periods/3/grid')
    // 旧期间的请求还在途中就切走
    await router.push('/periods/2/grid')

    const store = useGridStore()
    await waitFor(() => (store.period?.id === '2' ? true : null))
    expect(store.rows[0]?.recordId).toBe('RB-2')

    // 等慢响应（period 3）真正返回后，仍是 period 2 的数据
    await new Promise((resolve) => setTimeout(resolve, 800))
    expect(store.period?.id).toBe('2')
    expect(store.rows[0]?.recordId).toBe('RB-2')
  })
})
