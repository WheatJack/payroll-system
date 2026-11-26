import { HttpResponse, http, delay } from 'msw'
import type {
  ChangesetRequest,
  ChangesetResult,
  CompProfile,
  Employee,
  EmployeePage,
  GridRow,
  PayItem,
  Period,
  PeriodCreateRequest,
  RunProgress,
} from '@/api/models'
import { add, round, sub } from '@/utils/money'
import {
  COMPANIES,
  COMP_PROFILES,
  DEMO_TOKEN,
  DEMO_USER,
  DEPTS,
  EMPLOYEES,
  EMPLOYEE_ID_NOS,
  GRID_EXAMPLE,
  PAY_ITEMS,
  PERIODS,
  clone,
} from './fixtures'

/**
 * M1 端点的 MSW handlers。
 *
 * 原则：
 * - 响应包仍是 `{code,msg,data}`，但错误用**真实 HTTP 状态码**（R8）。
 * - 金额一律字符串，mock 内部也只做字符串运算（money 工具）。
 * - GET /periods/{id}/grid 直接返回 docs/api/examples/grid-response.json（截断示例：2 列 1 行）。
 */

const API = '/api/v1'

const ok = (data: unknown) => HttpResponse.json({ code: 0, msg: 'ok', data })
const fail = (status: number, msg: string, data: unknown = null) =>
  HttpResponse.json({ code: status, msg, data }, { status })

function requireAuth(request: Request): Response | null {
  const header = request.headers.get('Authorization') ?? ''
  return header === `Bearer ${DEMO_TOKEN}` ? null : fail(401, '未认证或登录已过期')
}

const state = {
  employees: clone(EMPLOYEES),
  idNos: { ...EMPLOYEE_ID_NOS },
  profiles: clone(COMP_PROFILES),
  periods: clone(PERIODS),
  items: clone(PAY_ITEMS),
  grid: clone(GRID_EXAMPLE),
  runs: new Map<string, { startedAt: number }>(),
  nextEmployeeId: EMPLOYEES.length + 1,
  nextProfileId: COMP_PROFILES.length + 1,
  nextPeriodId: 4,
}

const directionOf = (itemCode: string): number =>
  state.items.find((item) => item.itemCode === itemCode)?.direction ?? 1

function recomputeRow(row: GridRow): GridRow {
  let gross = '0.00'
  let deduction = '0.00'
  for (const [itemCode, amount] of Object.entries(row.items)) {
    if (directionOf(itemCode) === 2) deduction = add(deduction, amount)
    else gross = add(gross, amount)
  }
  row.grossAmount = gross
  row.deductionAmount = deduction
  row.netAmount = sub(gross, deduction)
  return row
}

function refreshPeriodTotals(periodId: string): void {
  const period = state.periods.find((item) => item.id === periodId)
  if (!period) return
  let gross = '0.00'
  let net = '0.00'
  for (const row of state.grid.rows) {
    gross = add(gross, row.grossAmount)
    net = add(net, row.netAmount)
  }
  period.employeeCount = state.grid.rows.length
  period.totalGross = gross
  period.totalNet = net
}

function maskIdNo(idNo: string): string {
  return idNo.length <= 4 ? '****' : `****${idNo.slice(-4)}`
}

function maskMobile(mobile: string): string {
  return mobile.length < 7 ? '***' : `${mobile.slice(0, 3)}****${mobile.slice(-4)}`
}

function previousDay(date: string): string {
  const [year, month, day] = date.split('-').map((part) => Number(part))
  const value = new Date(Date.UTC(year, month - 1, day))
  value.setUTCDate(value.getUTCDate() - 1)
  return value.toISOString().slice(0, 10)
}

function rangesOverlap(a: { from: string; to: string | null }, b: { from: string; to: string | null }): boolean {
  const aTo = a.to ?? '9999-12-31'
  const bTo = b.to ?? '9999-12-31'
  return a.from <= bTo && b.from <= aTo
}

export const handlers = [
  /* ------------------------------ 认证 ------------------------------ */

  http.post(`${API}/auth/login`, async ({ request }) => {
    const body = (await request.json()) as { username?: string; password?: string }
    await delay(120)
    if (!body?.username || !body?.password) {
      return fail(400, '用户名和密码不能为空')
    }
    if (body.username !== 'hr' || body.password !== 'hr123456') {
      return fail(400, '用户名或密码错误')
    }
    return ok({ token: DEMO_TOKEN, user: DEMO_USER })
  }),

  http.get(`${API}/auth/me`, ({ request }) => {
    const denied = requireAuth(request)
    return denied ?? ok(DEMO_USER)
  }),

  /* ------------------------------ 组织 ------------------------------ */

  http.get(`${API}/companies`, ({ request }) => requireAuth(request) ?? ok(COMPANIES)),

  http.get(`${API}/depts`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const companyId = new URL(request.url).searchParams.get('companyId')
    if (!companyId) return fail(400, 'companyId 不能为空')
    return ok(DEPTS.filter((dept) => dept.companyId === companyId))
  }),

  /* ------------------------------ 人员 ------------------------------ */

  http.get(`${API}/employees`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const search = new URL(request.url).searchParams
    const current = Math.max(1, Number(search.get('current') ?? '1'))
    const size = Math.max(1, Number(search.get('size') ?? '20'))
    const keyword = (search.get('keyword') ?? '').trim().toLowerCase()
    const deptId = search.get('deptId')
    const employeeType = search.get('employeeType')
    const status = search.get('status')

    let list = state.employees.filter((employee) => {
      if (keyword && !`${employee.empNo}${employee.name}`.toLowerCase().includes(keyword)) return false
      if (deptId && employee.deptId !== deptId) return false
      if (employeeType && employee.employeeType !== employeeType) return false
      if (status && String(employee.status) !== status) return false
      return true
    })
    list = [...list].sort((a, b) => a.empNo.localeCompare(b.empNo))
    const total = list.length
    const start = (current - 1) * size
    const page: EmployeePage = { records: list.slice(start, start + size), total, current, size }
    return ok(page)
  }),

  http.post(`${API}/employees`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const body = (await request.json()) as Record<string, string | number | null | undefined>
    if (!body.empNo || !body.name || !body.idNo || !body.deptId || !body.hireDate) {
      return fail(400, '工号、姓名、证件号、部门、入职日期为必填')
    }
    const empNo = String(body.empNo)
    const idNo = String(body.idNo)
    if (state.employees.some((employee) => employee.empNo === empNo)) {
      return fail(409, '工号已被占用')
    }
    if (Object.values(state.idNos).includes(idNo)) {
      return fail(409, '该证件号码已存在人员记录')
    }
    const employee: Employee = {
      id: String(state.nextEmployeeId++),
      companyId: String(body.companyId ?? DEMO_USER.companyId),
      empNo,
      name: String(body.name),
      gender: (Number(body.gender ?? 0) as 0 | 1 | 2) ?? 0,
      idType: String(body.idType ?? 'ID_CARD'),
      idNoMask: maskIdNo(idNo),
      mobileMask: body.mobile ? maskMobile(String(body.mobile)) : null,
      employeeType: (body.employeeType as Employee['employeeType']) ?? 'REGULAR',
      jobLevel: body.jobLevel ? String(body.jobLevel) : null,
      positionName: body.positionName ? String(body.positionName) : null,
      deptId: String(body.deptId),
      hireDate: String(body.hireDate),
      workStartDate: body.workStartDate ? String(body.workStartDate) : null,
      leaveDate: body.leaveDate ? String(body.leaveDate) : null,
      status: (Number(body.status ?? 1) as 1 | 2 | 3) ?? 1,
      remark: body.remark ? String(body.remark) : null,
    }
    state.employees.push(employee)
    state.idNos[employee.id] = idNo
    return ok(employee)
  }),

  http.get(`${API}/employees/:id`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const employee = state.employees.find((item) => item.id === params.id)
    return employee ? ok(employee) : fail(400, '人员不存在')
  }),

  http.put(`${API}/employees/:id`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const employee = state.employees.find((item) => item.id === params.id)
    if (!employee) return fail(400, '人员不存在')
    const body = (await request.json()) as Record<string, string | number | null | undefined>

    if (body.empNo && state.employees.some((item) => item.id !== employee.id && item.empNo === body.empNo)) {
      return fail(409, '工号已被占用')
    }
    if (body.idNo) {
      const idNo = String(body.idNo)
      const duplicated = Object.entries(state.idNos).some(([id, value]) => id !== employee.id && value === idNo)
      if (duplicated) return fail(409, '该证件号码已存在人员记录')
      state.idNos[employee.id] = idNo
      employee.idNoMask = maskIdNo(idNo)
    }
    if (body.mobile) employee.mobileMask = maskMobile(String(body.mobile))

    Object.assign(employee, {
      ...(body.empNo !== undefined ? { empNo: String(body.empNo) } : {}),
      ...(body.name !== undefined ? { name: String(body.name) } : {}),
      ...(body.gender !== undefined ? { gender: Number(body.gender) as 0 | 1 | 2 } : {}),
      ...(body.idType !== undefined ? { idType: String(body.idType) } : {}),
      ...(body.employeeType !== undefined ? { employeeType: body.employeeType as Employee['employeeType'] } : {}),
      ...(body.jobLevel !== undefined ? { jobLevel: body.jobLevel ? String(body.jobLevel) : null } : {}),
      ...(body.positionName !== undefined ? { positionName: body.positionName ? String(body.positionName) : null } : {}),
      ...(body.deptId !== undefined ? { deptId: String(body.deptId) } : {}),
      ...(body.hireDate !== undefined ? { hireDate: String(body.hireDate) } : {}),
      ...(body.workStartDate !== undefined ? { workStartDate: body.workStartDate ? String(body.workStartDate) : null } : {}),
      ...(body.leaveDate !== undefined ? { leaveDate: body.leaveDate ? String(body.leaveDate) : null } : {}),
      ...(body.status !== undefined ? { status: Number(body.status) as 1 | 2 | 3 } : {}),
      ...(body.remark !== undefined ? { remark: body.remark ? String(body.remark) : null } : {}),
    })
    return ok(employee)
  }),

  /* ---------------------------- 薪酬档案 ---------------------------- */

  http.get(`${API}/employees/:id/comp-profiles`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const list = state.profiles
      .filter((profile) => profile.employeeId === params.id)
      .sort((a, b) => (a.effectiveFrom < b.effectiveFrom ? -1 : 1))
    return ok(list)
  }),

  http.post(`${API}/comp-profiles`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const body = (await request.json()) as Partial<CompProfile> & { employeeId?: string }
    if (!body.employeeId || !body.effectiveFrom || !body.baseSalary) {
      return fail(400, '员工、生效日期与基本工资为必填')
    }
    const next = { from: String(body.effectiveFrom), to: null }
    const overlapped = state.profiles.some(
      (profile) =>
        profile.employeeId === body.employeeId &&
        profile.status === 1 &&
        rangesOverlap(next, { from: profile.effectiveFrom, to: profile.effectiveTo ?? null }),
    )
    if (overlapped) return fail(400, '生效区间与已有薪酬档案重叠')

    const profile: CompProfile = {
      id: String(state.nextProfileId++),
      employeeId: String(body.employeeId),
      effectiveFrom: next.from,
      effectiveTo: null,
      baseSalary: round(String(body.baseSalary), 2),
      changeReason: body.changeReason ?? null,
      status: 1,
      items: (body.items ?? []).map((item) => ({ itemCode: item.itemCode, amount: round(item.amount, 2) })),
    }
    state.profiles.push(profile)
    return ok(profile)
  }),

  http.put(`${API}/comp-profiles/:id`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const current = state.profiles.find((profile) => profile.id === params.id)
    if (!current) return fail(400, '薪酬档案不存在')
    const body = (await request.json()) as Partial<CompProfile>

    const effectiveFrom = body.effectiveFrom ? String(body.effectiveFrom) : current.effectiveFrom
    const baseSalary = body.baseSalary ? round(String(body.baseSalary), 2) : current.baseSalary
    const items = (body.items ?? current.items).map((item) => ({
      itemCode: item.itemCode,
      amount: round(item.amount, 2),
    }))

    // 与当前区间同一天生效：原地修改
    if (effectiveFrom <= current.effectiveFrom) {
      current.baseSalary = baseSalary
      current.items = items
      current.changeReason = body.changeReason ?? current.changeReason
      return ok(current)
    }

    // 否则截断旧区间（前一天结束）并新建一段
    current.effectiveTo = previousDay(effectiveFrom)
    const next: CompProfile = {
      id: String(state.nextProfileId++),
      employeeId: current.employeeId,
      effectiveFrom,
      effectiveTo: null,
      baseSalary,
      changeReason: body.changeReason ?? null,
      status: 1,
      items,
    }
    state.profiles.push(next)
    return ok(next)
  }),

  /* ------------------------------ 工资项 ------------------------------ */

  http.get(`${API}/items`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const search = new URL(request.url).searchParams
    const category = search.get('category')
    const status = search.get('status')
    const list = state.items
      .filter((item) => (category ? item.category === category : true))
      .filter((item) => (status !== null ? String(item.status) === status : true))
      .sort((a, b) => a.sortNo - b.sortNo)
    return ok(list)
  }),

  http.post(`${API}/items`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const body = (await request.json()) as Partial<PayItem> & { itemCode?: string; itemName?: string }
    if (!body.itemCode || !body.itemName) return fail(400, '科目编码与名称为必填')
    if (state.items.some((item) => item.itemCode === body.itemCode)) {
      return fail(409, '科目编码已存在')
    }
    const item: PayItem = {
      id: String(state.items.length + 100),
      companyId: body.companyId ?? DEMO_USER.companyId,
      itemCode: String(body.itemCode),
      itemName: String(body.itemName),
      category: body.category ?? 'VARIABLE',
      direction: body.direction ?? 1,
      dataType: body.dataType ?? 'AMOUNT',
      precisionScale: body.precisionScale ?? 2,
      includeInGross: body.includeInGross ?? true,
      editable: body.editable ?? true,
      importable: body.importable ?? true,
      sortNo: body.sortNo ?? 0,
      status: body.status ?? 1,
    }
    state.items.push(item)
    return ok(item)
  }),

  http.put(`${API}/items/:id`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const item = state.items.find((entry) => entry.id === params.id)
    if (!item) return fail(400, '工资项不存在')
    const body = (await request.json()) as Partial<PayItem>

    const used = new Set(state.grid.columns.map((column) => column.itemCode))
    for (const row of state.grid.rows) {
      for (const code of Object.keys(row.items)) used.add(code)
    }
    if (used.has(item.itemCode)) {
      if (body.category !== undefined && body.category !== item.category) {
        return fail(409, '已被工资表使用的科目禁止修改类别与方向')
      }
      if (body.direction !== undefined && body.direction !== item.direction) {
        return fail(409, '已被工资表使用的科目禁止修改类别与方向')
      }
    }
    Object.assign(item, body)
    return ok(item)
  }),

  /* ------------------------------ 期间 ------------------------------ */

  http.get(`${API}/periods`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const companyId = new URL(request.url).searchParams.get('companyId') ?? DEMO_USER.companyId
    const list = state.periods
      .filter((period) => period.companyId === companyId)
      .sort((a, b) => (a.periodYm < b.periodYm ? 1 : -1))
    return ok(list)
  }),

  http.post(`${API}/periods`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const body = (await request.json()) as PeriodCreateRequest
    if (!body.periodYm) return fail(400, '工资年月为必填')
    const companyId = body.companyId ?? DEMO_USER.companyId
    const existing = state.periods.find(
      (period) => period.companyId === companyId && period.periodYm === body.periodYm,
    )
    if (existing) return ok(existing)

    const period: Period = {
      id: String(state.nextPeriodId++),
      companyId,
      periodYm: body.periodYm,
      status: 'DRAFT',
      employeeCount: 0,
      totalGross: '0.00',
      totalNet: '0.00',
      hpd: '21.75',
    }
    state.periods.push(period)
    return ok(period)
  }),

  http.post(`${API}/periods/:id/generate`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const period = state.periods.find((item) => item.id === params.id)
    if (!period) return fail(400, '期间不存在')
    if (period.status !== 'DRAFT' && period.status !== 'EDITING') {
      return fail(409, '期间已锁定，禁止修改')
    }
    const runId = String(state.runs.size + 1)
    state.runs.set(runId, { startedAt: Date.now() })
    return ok({ runId })
  }),

  http.get(`${API}/runs/:runId`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const run = state.runs.get(String(params.runId))
    if (!run) return fail(400, '批次不存在')
    const elapsed = Date.now() - run.startedAt
    const finished = elapsed > 1200
    const progress: RunProgress = {
      status: finished ? 'SUCCESS' : 'RUNNING',
      total: 1,
      success: finished ? 1 : 0,
      failed: 0,
      errors: [],
      skipped: { E999: '未配置薪酬档案' },
    }
    return ok(progress)
  }),

  http.post(`${API}/periods/:id/lock`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const period = state.periods.find((item) => item.id === params.id)
    if (!period) return fail(400, '期间不存在')
    if (period.status !== 'EDITING') return fail(409, '期间状态不允许锁定')
    period.status = 'LOCKED'
    return ok(period)
  }),

  http.post(`${API}/periods/:id/unlock`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const body = (await request.json()) as { reason?: string }
    if (!body?.reason) return fail(400, '解锁原因必填')
    const period = state.periods.find((item) => item.id === params.id)
    if (!period) return fail(400, '期间不存在')
    if (period.status !== 'LOCKED') return fail(409, '期间状态不允许解锁')
    period.status = 'EDITING'
    return ok(period)
  }),

  /* ------------------------------ 编制台 ------------------------------ */

  http.get(`${API}/periods/:id/grid`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const period = state.periods.find((item) => item.id === params.id)
    if (!period) return fail(400, '期间不存在')
    return ok({
      period: { id: period.id, periodYm: period.periodYm, status: period.status },
      columns: clone(state.grid.columns),
      rows: clone(state.grid.rows),
      total: state.grid.rows.length,
    })
  }),

  http.patch(`${API}/periods/:id/records`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const period = state.periods.find((item) => item.id === params.id)
    if (!period) return fail(400, '期间不存在')
    if (period.status !== 'EDITING' && period.status !== 'DRAFT') {
      return fail(409, '期间已锁定，禁止修改')
    }
    const body = (await request.json()) as ChangesetRequest
    if (!Array.isArray(body?.changes) || body.changes.length === 0) {
      return fail(400, 'changes 不能为空')
    }

    // 先整体检查乐观锁，任一冲突则整体失败（契约要求）
    const conflicts: Array<{ recordId: string }> = []
    for (const change of body.changes) {
      const row = state.grid.rows.find((item) => item.recordId === change.recordId)
      if (!row || row.version !== change.version) conflicts.push({ recordId: change.recordId })
    }
    if (conflicts.length > 0) {
      return fail(409, '数据已被他人修改，请刷新', { conflicts })
    }

    const updatedRows: GridRow[] = []
    for (const change of body.changes) {
      const row = state.grid.rows.find((item) => item.recordId === change.recordId)!
      for (const item of change.items) {
        row.items[item.itemCode] = round(item.amount, 2)
      }
      row.version += 1
      recomputeRow(row)
      updatedRows.push(clone(row))
    }
    refreshPeriodTotals(period.id)

    const result: ChangesetResult = { updated: updatedRows.length, rows: updatedRows }
    return ok(result)
  }),
]
