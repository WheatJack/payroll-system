/**
 * 人员导入向导的核心约束：failRows > 0 禁止提交（前端拦截 + 后端 409 兜底）。
 */
import { setupServer } from 'msw/node'
import { HttpResponse, http } from 'msw'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, test } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { handlers } from '@/api/mock/handlers'
import { DEMO_USER } from '@/api/mock/fixtures'
import { useAuthStore } from '@/stores/auth'
import { useEmployeeImport } from '../useEmployeeImport'

const server = setupServer(...handlers)

let commitCalls = 0
let validateFailRows = 0

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  commitCalls = 0
  validateFailRows = 0
})
afterAll(() => server.close())

function mockImportApi(): void {
  server.use(
    http.post('/api/v1/import/employees/validate', () =>
      HttpResponse.json({
        code: 0,
        msg: 'ok',
        data: {
          batchId: 'E-1',
          totalRows: 10,
          successRows: 10 - validateFailRows,
          failRows: validateFailRows,
          status: 'VALIDATED',
          errors:
            validateFailRows > 0
              ? [{ rowNo: 3, columnName: '证件号', rawValue: 'abc', errorCode: 'INVALID_ID', errorMsg: '证件号格式不正确' }]
              : [],
        },
      }),
    ),
    http.post('/api/v1/import/employees/:batchId/commit', () => {
      commitCalls += 1
      return HttpResponse.json({
        code: 0,
        msg: 'ok',
        data: { batchId: 'E-1', status: 'COMMITTED', successRows: 10, failRows: 0 },
      })
    }),
  )
}

function seedAuth(permissions = DEMO_USER.permissions): void {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.applyUser({ ...DEMO_USER, permissions })
}

const file = () => new File(['PK mock'], 'employees.xlsx', { type: 'application/vnd.ms-excel' })

describe('人员批量导入：failRows 拦截', () => {
  beforeEach(() => {
    seedAuth()
    mockImportApi()
  })

  test('failRows > 0：提交按钮禁用并给出原因，且不会发出提交请求', async () => {
    validateFailRows = 2
    const state = useEmployeeImport()
    const result = await state.validate(file())

    expect(result?.failRows).toBe(2)
    expect(state.commitEnabled.value).toBe(false)
    expect(state.blockedReason.value).toContain('2 行失败')

    const committed = await state.commit()
    expect(committed).toBeNull()
    expect(commitCalls).toBe(0) // 前端就拦住，不靠后端 409
  })

  test('预校验全部通过：可提交，成功返回 COMMITTED', async () => {
    const state = useEmployeeImport()
    await state.validate(file())

    expect(state.commitEnabled.value).toBe(true)
    expect(state.blockedReason.value).toBeNull()

    const committed = await state.commit()
    expect(committed?.status).toBe('COMMITTED')
    expect(commitCalls).toBe(1)
    expect(state.batch.value?.status).toBe('COMMITTED')
  })

  test('没有 payroll:import:submit：不可提交并说明原因', async () => {
    seedAuth(['payroll:record:view'])
    const state = useEmployeeImport()
    await state.validate(file())

    expect(state.commitEnabled.value).toBe(false)
    expect(state.blockedReason.value).toContain('payroll:import:submit')
    expect(await state.commit()).toBeNull()
    expect(commitCalls).toBe(0)
  })
})

describe('人员批量导入：后端 409 兜底', () => {
  beforeEach(() => {
    seedAuth()
    server.use(
      http.post('/api/v1/import/employees/validate', () =>
        HttpResponse.json({
          code: 0,
          msg: 'ok',
          data: { batchId: 'E-2', totalRows: 5, successRows: 5, failRows: 0, status: 'VALIDATED', errors: [] },
        }),
      ),
      http.post('/api/v1/import/employees/:batchId/commit', () =>
        HttpResponse.json({ code: 409, msg: '预校验存在 3 行失败，禁止提交', data: { batchId: 'E-2', failRows: 3 } }, { status: 409 }),
      ),
    )
  })

  test('提交返回 409 且带 failRows：提示禁止提交并保留批次（可修正后重传）', async () => {
    const state = useEmployeeImport()
    await state.validate(file())
    const result = await state.commit()

    expect(result).toBeNull()
    expect(state.errorMessage.value).toContain('3 行失败')
    expect(state.errorMessage.value).toContain('禁止提交')
    expect(state.batch.value?.batchId).toBe('E-2')
  })
})
