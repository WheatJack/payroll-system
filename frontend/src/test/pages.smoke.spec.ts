/**
 * 逐页冒烟：每个主要页面在 MSW mock 下真实挂载，断言「页面里确实有数据」，
 * 而不只是登录/菜单层面的验证。
 *
 * 约定：mock 数据字段类型必须与契约一致（金额/工龄是字符串，人数是数字）；
 * 唯一例外是「后端字段类型漂移」的回归用例，专门验证前端不白屏。
 */
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, test } from 'vitest'
import { handlers } from '@/api/mock/handlers'
import { EMPLOYEES } from '@/api/mock/fixtures'
import EmployeeListView from '@/views/employee/EmployeeListView.vue'
import ItemListView from '@/views/item/ItemListView.vue'
import PeriodListView from '@/views/period/PeriodListView.vue'
import PayrollGridView from '@/views/period/PayrollGridView.vue'
import MonthlyReportView from '@/views/report/MonthlyReportView.vue'
import AnnualReportView from '@/views/report/AnnualReportView.vue'
import UserListView from '@/views/system/UserListView.vue'
import RoleListView from '@/views/system/RoleListView.vue'
import DeptTreeView from '@/views/system/DeptTreeView.vue'
import CompanyListView from '@/views/system/CompanyListView.vue'
import ConfigView from '@/views/system/ConfigView.vue'
import { mountPage, tableRows, waitFor, type MountedPage } from '@/test/pageTestUtils'

const server = setupServer(...handlers)

const mounted: MountedPage[] = []

async function mount(component: Parameters<typeof mountPage>[0], path: string): Promise<MountedPage> {
  const page = await mountPage(component, path)
  mounted.push(page)
  return page
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  while (mounted.length > 0) mounted.pop()?.wrapper.unmount()
  server.resetHandlers()
})
afterAll(() => server.close())

describe('逐页冒烟（数据必须真的渲染出来）', () => {
  test('人员管理：有数据行，工龄显示 6.00，部门显示名称', async () => {
    const { wrapper } = await mount(EmployeeListView, '/employees')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBeGreaterThan(0)

    const text = wrapper.text()
    expect(text).toContain('6.00')
    expect(text).toContain('北京研发部')
    expect(text).not.toContain('undefined')
  })

  test('人员管理：后端把工龄返回成数字时也不白屏，按 6.00 展示', async () => {
    server.use(
      http.get('/api/v1/employees', () =>
        HttpResponse.json({
          code: 0,
          msg: 'ok',
          data: {
            records: [{ ...EMPLOYEES[0], seniorityYears: 6, socialSeniorityYears: 7.5 }],
            total: 1,
            current: 1,
            size: 20,
          },
        }),
      ),
    )
    const { wrapper } = await mount(EmployeeListView, '/employees')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBe(1)
    expect(wrapper.text()).toContain('6.00')
  })

  test('工资项：有数据行', async () => {
    const { wrapper } = await mount(ItemListView, '/items')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('基本工资')
  })

  test('工资期间：有数据行', async () => {
    const { wrapper } = await mount(PeriodListView, '/periods')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('2026-11')
  })

  test('编制台：表头与数据行都存在', async () => {
    const { wrapper } = await mount(PayrollGridView, '/periods/3/grid')
    const headers = await waitFor(() => {
      const found = wrapper.findAll('.vxe-header--column')
      return found.length > 0 ? found : null
    })
    const rows = await waitFor(() => {
      const found = wrapper.findAll('.vxe-table--body tbody tr')
      return found.length > 0 ? found : null
    })
    expect(headers.length).toBeGreaterThan(0)
    expect(rows.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('绩效奖金')
  })

  test('月度报表：有数据行且合计按 money 计算', async () => {
    server.use(
      http.get('/api/v1/reports/monthly', () =>
        HttpResponse.json({
          code: 0,
          msg: 'ok',
          data: [
            { companyId: '1', periodYm: '2026-10', deptId: '1', deptName: '北京研发部', employeeCount: 2, grossAmount: '8600.00', deductionAmount: '2150.00', netAmount: '6450.00' },
            { companyId: '1', periodYm: '2026-10', deptId: '2', deptName: '北京财务部', employeeCount: 1, grossAmount: '7200.00', deductionAmount: '1800.00', netAmount: '5400.00' },
          ],
        }),
      ),
    )
    const { wrapper } = await mount(MonthlyReportView, '/reports/monthly')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBe(2)
    const footer = wrapper.find('.el-table__footer').text()
    expect(footer).toContain('15,800.00') // 8600 + 7200
    expect(footer).toContain('11,850.00') // 6450 + 5400
  })

  test('年度报表：有数据行且合计正确', async () => {
    server.use(
      http.get('/api/v1/reports/annual', () =>
        HttpResponse.json({
          code: 0,
          msg: 'ok',
          data: [
            { year: '2026', groupBy: 'EMP', employeeId: '1', empNo: 'E001', empName: '张三', employeeCount: 1, grossAmount: '8600.00', deductionAmount: '2150.00', netAmount: '6450.00' },
            { year: '2026', groupBy: 'EMP', employeeId: '2', empNo: 'E002', empName: '李四', employeeCount: 1, grossAmount: '7200.00', deductionAmount: '1800.00', netAmount: '5400.00' },
          ],
        }),
      ),
    )
    const { wrapper } = await mount(AnnualReportView, '/reports/annual')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBe(2)
    expect(wrapper.find('.el-table__footer').text()).toContain('15,800.00')
  })

  test('用户管理：有数据行', async () => {
    const { wrapper } = await mount(UserListView, '/system/users')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('hr')
  })

  test('角色与授权：有数据行', async () => {
    const { wrapper } = await mount(RoleListView, '/system/roles')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('系统管理员')
  })

  test('部门管理：树有节点', async () => {
    const { wrapper } = await mount(DeptTreeView, '/system/depts')
    const nodes = await waitFor(() => {
      const found = wrapper.findAll('.el-table__body tbody tr')
      return found.length > 0 ? found : null
    })
    expect(nodes.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('北京研发部')
  })

  test('法人主体：有数据行', async () => {
    const { wrapper } = await mount(CompanyListView, '/system/companies')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('北京总部')
  })

  test('系统参数：参数条数 > 0 且显示当前值', async () => {
    const { wrapper } = await mount(ConfigView, '/system/config')
    const rows = await waitFor(() => {
      const found = tableRows(wrapper)
      return found.length > 0 ? found : null
    })
    expect(rows.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('STANDARD_WORK_DAYS')
    expect(wrapper.text()).toContain('21.75')
  })
})
