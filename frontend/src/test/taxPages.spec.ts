/**
 * 个税社保三个界面：
 * - 参保信息为空时的明确提示（否则 HR 以为社保算错了）
 * - 专项附加扣除的中文映射
 * - 年度个税核对合计行（金额可加总、税负率不可加总）
 */
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { setupServer } from 'msw/node'
import { HttpResponse, http } from 'msw'
import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Component } from 'vue'
import { handlers } from '@/api/mock/handlers'
import { DEMO_TOKEN, DEMO_USER } from '@/api/mock/fixtures'
import { setToken } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import InsurancePanel from '@/views/employee/InsurancePanel.vue'
import SpecialDeductionPanel from '@/views/employee/SpecialDeductionPanel.vue'
import AnnualTaxReportView from '@/views/report/AnnualTaxReportView.vue'
import { mountPage, tableRows, waitFor } from '@/test/pageTestUtils'

const server = setupServer(...handlers)
const mounted: Array<{ unmount: () => void }> = []

function seedAuth(): void {
  setActivePinia(createPinia())
  // 请求拦截器从 localStorage 取 token；面板直接挂载也必须预置
  setToken(DEMO_TOKEN)
  const auth = useAuthStore()
  auth.token = DEMO_TOKEN
  auth.applyUser(DEMO_USER)
}

async function mountPanel(component: Component, employeeId = '1') {
  seedAuth()
  const wrapper = mount(component, {
    props: { employeeId },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  while (mounted.length > 0) mounted.pop()?.unmount()
  server.resetHandlers()
})
afterAll(() => server.close())

describe('参保信息', () => {
  test('未配置时明确提示「社保公积金不会计入」', async () => {
    server.use(
      http.get('/api/v1/employees/:id/social-insurance', () =>
        HttpResponse.json({ code: 0, msg: 'ok', data: [] }),
      ),
    )
    const wrapper = await mountPanel(InsurancePanel)
    await waitFor(() => (wrapper.text().includes('未配置参保信息') ? true : null))

    expect(wrapper.text()).toContain('未配置参保信息，社保公积金不会计入')
  })

  test('输入不存在的城市编码：给出可读提示并阻止提交（不静默失败）', async () => {
    server.use(
      http.get('/api/v1/insurance-params', () => HttpResponse.json({ code: 0, msg: 'ok', data: [] })),
    )
    const wrapper = await mountPanel(InsurancePanel)
    await waitFor(() => (wrapper.text().includes('新增参保信息') ? true : null))

    ;[...wrapper.findAll('button')].find((button) => button.text().includes('新增参保信息'))!.trigger('click')
    await flushPromises()

    // allow-create：输入编码后回车创建选项
    const cityInput = wrapper.find('.el-dialog .el-select input')
    await cityInput.setValue('NOPE')
    await cityInput.trigger('keydown', { key: 'Enter' })
    await waitFor(() => (document.body.textContent?.includes('该城市编码不存在') ? true : null))

    expect(document.body.textContent).toContain('该城市编码不存在或未配置险种参数')
  })

  test('展示城市中文名、自动计算提示与公积金比例', async () => {
    const wrapper = await mountPanel(InsurancePanel)
    await waitFor(() => (wrapper.text().includes('北京') ? true : null))

    expect(wrapper.text()).toContain('北京')
    expect(wrapper.text()).toContain('按近 12 期平均应发自动计算')
    expect(wrapper.text()).toContain('12.00%')
  })
})

describe('专项附加扣除', () => {
  test('枚举类型以中文展示，月额格式化', async () => {
    const wrapper = await mountPanel(SpecialDeductionPanel)
    await waitFor(() => (wrapper.text().includes('子女教育') ? true : null))

    expect(wrapper.text()).toContain('子女教育')
    expect(wrapper.text()).toContain('住房贷款利息')
    expect(wrapper.text()).toContain('2,000.00')
    expect(wrapper.text()).not.toContain('CHILD_EDU')
  })
})

describe('年度个税核对', () => {
  test('合计行：金额逐列加总，税负率显示 — 而不是加总', async () => {
    const page = await mountPage(AnnualTaxReportView, '/reports/tax')
    mounted.push(page.wrapper)
    await waitFor(() => (tableRows(page.wrapper).length > 0 ? true : null))

    const body = page.wrapper.text()
    expect(body).toContain('张三')
    expect(body).toContain('0.30%') // 0.0030 的展示

    const footer = page.wrapper.find('.el-table__footer').text()
    // 累计收入 120000 + 48000；累计已预扣 360 + 324
    expect(footer).toContain('168,000.00')
    expect(footer).toContain('684.00')
    // 计薪月数 12 + 6
    expect(footer).toContain('18')
    // 税负率不可加总
    expect(footer).toContain('—')
    expect(footer).not.toContain('0.98%')
  })

  test('后端返回旧版数组结构时给出明确错误，而不是静默空数据或白屏', async () => {
    server.use(
      http.get('/api/v1/reports/annual-tax', () =>
        // 旧版后端返回数组而不是 { rows, excludedPeriods }
        HttpResponse.json({ code: 0, msg: 'ok', data: [] }),
      ),
    )
    const page = await mountPage(AnnualTaxReportView, '/reports/tax')
    mounted.push(page.wrapper)
    await waitFor(() => (page.wrapper.text().includes('服务端返回结构') ? true : null))

    expect(page.wrapper.text()).toContain('服务端返回结构不是 { rows, excludedPeriods }')
  })

  test('导出走服务端 xlsx（ANNUAL_TAX），不再生成前端 CSV', async () => {
    const exportBodies: Array<{ reportType?: string; params?: { year?: string } }> = []
    server.use(
      http.post('/api/v1/reports/export', async ({ request }) => {
        exportBodies.push((await request.json()) as { reportType?: string; params?: { year?: string } })
        return new HttpResponse(new TextEncoder().encode('PK mock annual tax'), {
          status: 200,
          headers: {
            'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            'Content-Disposition': 'attachment; filename="annual_tax-2025.xlsx"',
          },
        })
      }),
    )

    const page = await mountPage(AnnualTaxReportView, '/reports/tax')
    mounted.push(page.wrapper)
    await waitFor(() => (tableRows(page.wrapper).length > 0 ? true : null))

    const captured: string[] = []
    const origCreate = URL.createObjectURL
    const origClick = HTMLAnchorElement.prototype.click
    URL.createObjectURL = (blob: Blob) => {
      captured.push(blob.type)
      return origCreate(blob)
    }
    HTMLAnchorElement.prototype.click = function () {
      /* 阻止 jsdom 导航 */
    }
    try {
      await page.wrapper.findAll('button').find((button) => button.text().includes('导出 xlsx'))!.trigger('click')
      await waitFor(() => (captured.length > 0 ? true : null))
    } finally {
      URL.createObjectURL = origCreate
      HTMLAnchorElement.prototype.click = origClick
    }

    // 请求体证明走的是服务端 ANNUAL_TAX 导出
    expect(exportBodies).toHaveLength(1)
    expect(exportBodies[0].reportType).toBe('ANNUAL_TAX')
    expect(exportBodies[0].params?.year).toBe('2025')
    // 下载的是 xlsx 而不是 CSV
    expect(captured[0]).toContain('spreadsheetml')
    expect(captured[0]).not.toContain('csv')
  })
})
