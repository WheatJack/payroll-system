import { HttpResponse, http, delay } from 'msw'
import type { SystemConfig } from '@/api/config'
import type {
  ChangesetRequest,
  ChangesetResult,
  CompanyCreateRequest,
  CompanyUpdateRequest,
  CompProfile,
  DeptCreateRequest,
  DeptUpdateRequest,
  Employee,
  EmployeePage,
  ExportRequest,
  GridRow,
  ImportCommitResult,
  ImportError,
  ImportValidateResult,
  PayItem,
  Period,
  PeriodCreateRequest,
  Role,
  RoleCreateRequest,
  RoleUpdateRequest,
  CurrentUser,
  RunProgress,
  User,
  UserCreateRequest,
  UserUpdateRequest,
} from '@/api/models'
import { add, round, sub } from '@/utils/money'
import {
  ANNUAL_REPORT_EMP_ROWS,
  ANNUAL_REPORT_ROWS,
  COMPANIES,
  COMP_PROFILES,
  CONFIGS,
  DEMO_TOKEN,
  DEMO_TOKENS,
  DEMO_USER,
  DEPTS,
  EMPLOYEES,
  EMPLOYEE_ID_NOS,
  EMPLOYEE_MOBILES,
  GRID_EXAMPLE,
  MONTHLY_REPORT_ROWS,
  PAY_ITEMS,
  PERIODS,
  PERMISSIONS,
  ROLES,
  USERS,
  VIEWER_USER,
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
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

const ok = (data: unknown) => HttpResponse.json({ code: 0, msg: 'ok', data })
const fail = (status: number, msg: string, data: unknown = null) =>
  HttpResponse.json({ code: status, msg, data }, { status })

function currentUserOf(request: Request): CurrentUser | null {
  const header = request.headers.get('Authorization') ?? ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : ''
  return DEMO_TOKENS[token] ?? null
}

function requireAuth(request: Request): Response | null {
  return currentUserOf(request) ? null : fail(401, '未认证或登录已过期')
}

function hasPermission(request: Request, code: string): boolean {
  return currentUserOf(request)?.permissions.includes(code) ?? false
}

/**
 * 系统参数的 mock 持久化：放到 sessionStorage，整页刷新后仍保持（等价于「读后端」）。
 * 仅 mock 使用，真实环境数据来自 sys_config 表。
 * 注意：必须定义在 `state` 之前——`state` 初始化时会同步调用 loadPersistedConfigs()，
 * 否则 const 的 TDZ 会让读取直接抛 ReferenceError。
 */
const CONFIG_STORAGE_KEY = 'payroll.mock.configs'

function loadPersistedConfigs(): SystemConfig[] {
  try {
    const raw = sessionStorage.getItem(CONFIG_STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as SystemConfig[]
      if (Array.isArray(parsed) && parsed.length > 0) return parsed
    }
  } catch {
    // 存储不可用或 mock 缓存损坏时退回种子数据
  }
  return clone(CONFIGS)
}

function persistConfigs(): void {
  try {
    sessionStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(state.configs))
  } catch {
    // 忽略存储不可用
  }
}

const state = {
  employees: clone(EMPLOYEES),
  idNos: { ...EMPLOYEE_ID_NOS },
  profiles: clone(COMP_PROFILES),
  periods: clone(PERIODS),
  items: clone(PAY_ITEMS),
  grid: clone(GRID_EXAMPLE),
  companies: clone(COMPANIES),
  depts: clone(DEPTS),
  users: clone(USERS),
  roles: clone(ROLES),
  configs: loadPersistedConfigs(),
  runs: new Map<string, { startedAt: number }>(),
  nextEmployeeId: EMPLOYEES.length + 1,
  nextProfileId: COMP_PROFILES.length + 1,
  nextPeriodId: 4,
  nextUserId: USERS.length + 1,
  nextRoleId: ROLES.length + 1,
  nextDeptId: 20,
  nextCompanyId: 3,
}

/** 导入批次（M1.5）：预校验持久化，提交后标记已提交，同名文件拒绝重复导入。 */
const importState = {
  batches: new Map<
    string,
    { result: ImportValidateResult; fileName: string; committed: boolean; readyAt?: number }
  >(),
  committedFileNames: new Set<string>(),
  nextId: 1,
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
    if (body.username === 'hr' && body.password === 'hr123456') {
      return ok({ token: DEMO_TOKEN, user: DEMO_USER })
    }
    if (body.username === 'viewer' && body.password === 'viewer123') {
      return ok({ token: 'mock-viewer-token', user: VIEWER_USER })
    }
    return fail(400, '用户名或密码错误')
  }),

  http.get(`${API}/auth/me`, ({ request }) => {
    const denied = requireAuth(request)
    return denied ?? ok(currentUserOf(request))
  }),

  /* ------------------------------ 组织 ------------------------------ */

  http.get(`${API}/companies`, ({ request }) => requireAuth(request) ?? ok(state.companies)),

  http.get(`${API}/depts`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const companyId = new URL(request.url).searchParams.get('companyId')
    if (!companyId) return fail(400, 'companyId 不能为空')
    return ok(state.depts.filter((dept) => dept.companyId === companyId))
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

  http.get(`${API}/employees/:id/sensitive`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'payroll:sensitive:view')) return fail(403, '无权限执行该操作')
    const employee = state.employees.find((item) => item.id === params.id)
    if (!employee) return fail(400, '人员不存在')
    // 每次查看都写审计（mock 只打日志）
    console.info('[mock] 敏感信息查看审计', { operator: currentUserOf(request)?.userId, employeeId: employee.id })
    return ok({
      idNo: state.idNos[employee.id] ?? '—',
      mobile: EMPLOYEE_MOBILES[employee.id] ?? null,
    })
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
      deptName: DEPTS.find((dept) => dept.id === String(body.deptId))?.deptName ?? '',
      hireDate: String(body.hireDate),
      // 工龄由后端按入职日期计算；mock 新建时先给 0
      seniorityYears: '0.00',
      socialSeniorityYears: null,
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
      ...(body.deptId !== undefined
        ? {
            deptId: String(body.deptId),
            deptName: DEPTS.find((dept) => dept.id === String(body.deptId))?.deptName ?? '',
          }
        : {}),
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
      // 业务警告：不足月折算 + 节假日未配置（直接关系到会不会多发钱）
      warnings: finished
        ? ['本期有 1 人按不足月折算，但法定节假日未配置，折算天数可能偏多']
        : [],
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

  /* ------------------------------ 导入 ------------------------------ */

  http.get(`${API}/import/employee-template`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'payroll:import:submit')) return fail(403, '无权限执行该操作')
    return new HttpResponse(new Blob(['PK\u0003\u0004 mock employee template'], { type: XLSX_MIME }), {
      status: 200,
      headers: {
        'Content-Type': XLSX_MIME,
        'Content-Disposition': 'attachment; filename="employee-import-template.xlsx"',
      },
    })
  }),

  http.post(`${API}/import/employees/validate`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'payroll:import:submit')) return fail(403, '无权限执行该操作')
    const form = await request.formData()
    const file = form.get('file')
    if (!(file instanceof File)) return fail(400, 'file 必填')
    if (importState.committedFileNames.has(file.name)) return fail(409, '同一文件已提交过，请勿重复导入')

    const batchId = `E${importState.nextId++}`
    const errors: ImportError[] = /err|bad/i.test(file.name)
      ? [
          { rowNo: 2, columnName: '证件号', rawValue: 'abc', errorCode: 'INVALID_ID_NO', errorMsg: '证件号格式不正确' },
          { rowNo: 4, columnName: '部门编码', rawValue: 'NOPE', errorCode: 'DEPT_NOT_FOUND', errorMsg: '部门编码不存在' },
        ]
      : []
    const result: ImportValidateResult = {
      batchId,
      totalRows: 8,
      successRows: 8 - errors.length,
      failRows: errors.length,
      status: 'VALIDATED',
      errors,
    }
    importState.batches.set(batchId, { result, fileName: file.name, committed: false })
    return ok(result)
  }),

  http.post(`${API}/import/employees/:batchId/commit`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'payroll:import:submit')) return fail(403, '无权限执行该操作')
    const entry = importState.batches.get(String(params.batchId))
    if (!entry) return fail(400, '批次不存在')
    if (entry.committed) return fail(409, '该批次已提交，不能重复提交')
    if (entry.result.status === 'VALIDATING') return fail(409, '预校验进行中，请稍候再提交')
    if (entry.result.failRows > 0) {
      return fail(409, `预校验存在 ${entry.result.failRows} 行失败，禁止提交`, {
        batchId: entry.result.batchId,
        failRows: entry.result.failRows,
      })
    }
    entry.committed = true
    entry.result = { ...entry.result, status: 'COMMITTED' }
    importState.committedFileNames.add(entry.fileName)
    const result: ImportCommitResult = {
      batchId: entry.result.batchId,
      status: 'COMMITTED',
      successRows: entry.result.successRows,
      failRows: 0,
    }
    return ok(result)
  }),

  http.get(`${API}/import/template`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'payroll:import:submit')) return fail(403, '无权限执行该操作')
    const search = new URL(request.url).searchParams
    const periodId = search.get('periodId')
    const bizType = search.get('bizType')
    if (!periodId || !bizType) return fail(400, 'periodId 与 bizType 必填')
    const filename = `import-template-${bizType}-${periodId}.xlsx`
    return new HttpResponse(new Blob([`PK\u0003\u0004 mock template ${bizType}`], { type: XLSX_MIME }), {
      status: 200,
      headers: { 'Content-Type': XLSX_MIME, 'Content-Disposition': `attachment; filename="${filename}"` },
    })
  }),

  http.post(`${API}/import/validate`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'payroll:import:submit')) return fail(403, '无权限执行该操作')
    const form = await request.formData()
    const file = form.get('file')
    const periodId = form.get('periodId')
    const bizType = form.get('bizType')
    const importMode = form.get('importMode')
    if (!(file instanceof File) || !periodId || !bizType) return fail(400, 'file、periodId、bizType 必填')
    // APPEND 已下线：与后端保持一致，直接 400
    if (importMode === 'APPEND') return fail(400, 'APPEND 模式已下线，请使用 UPSERT 或 INSERT_ONLY')
    if (importState.committedFileNames.has(file.name)) {
      return fail(409, '同一文件已提交过，请勿重复导入')
    }

    const batchId = String(importState.nextId++)
    // 文件名含 err/bad 时演示失败行，便于验证「failRows>0 禁止提交」
    const errors: ImportError[] = /err|bad/i.test(file.name)
      ? [
          { rowNo: 3, columnName: '基本工资', rawValue: 'abc', errorCode: 'INVALID_AMOUNT', errorMsg: '金额格式不正确' },
          { rowNo: 5, columnName: '工号', rawValue: 'E999', errorCode: 'EMPLOYEE_NOT_FOUND', errorMsg: '工号不存在' },
        ]
      : []
    const result: ImportValidateResult = {
      batchId,
      totalRows: 10,
      successRows: 10 - errors.length,
      failRows: errors.length,
      // 文件名含 slow 时演示异步校验：先返回 VALIDATING，2.5s 后变 VALIDATED
      status: /slow/i.test(file.name) ? 'VALIDATING' : 'VALIDATED',
      errors,
    }
    importState.batches.set(batchId, {
      result,
      fileName: file.name,
      committed: false,
      ...(/slow/i.test(file.name) ? { readyAt: Date.now() + 2500 } : {}),
    })
    return ok(result)
  }),

  http.get(`${API}/import/:batchId/errors`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'payroll:export')) return fail(403, '无权限执行该操作')
    const entry = importState.batches.get(String(params.batchId))
    if (!entry) return fail(400, '批次不存在')
    const filename = `import-errors-${entry.result.batchId}.xlsx`
    return new HttpResponse(new Blob([`PK\u0003\u0004 mock errors ${entry.result.failRows}`], { type: XLSX_MIME }), {
      status: 200,
      headers: { 'Content-Type': XLSX_MIME, 'Content-Disposition': `attachment; filename="${filename}"` },
    })
  }),

  http.get(`${API}/import/:batchId`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const entry = importState.batches.get(String(params.batchId))
    if (!entry) return fail(400, '批次不存在或已过期')
    // 异步校验完成
    if (entry.readyAt !== undefined && Date.now() >= entry.readyAt) {
      entry.result = { ...entry.result, status: 'VALIDATED' }
      entry.readyAt = undefined
    }
    return ok({ ...entry.result, status: entry.committed ? 'COMMITTED' : entry.result.status })
  }),

  http.post(`${API}/import/:batchId/commit`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'payroll:import:submit')) return fail(403, '无权限执行该操作')
    const entry = importState.batches.get(String(params.batchId))
    if (!entry) return fail(400, '批次不存在')
    if (entry.committed) return fail(409, '该批次已提交，不能重复提交')
    if (entry.result.status === 'VALIDATING') return fail(409, '预校验进行中，请稍候再提交')
    if (entry.result.failRows > 0) {
      return fail(409, `预校验存在 ${entry.result.failRows} 行失败，禁止提交`, {
        batchId: entry.result.batchId,
        failRows: entry.result.failRows,
      })
    }
    entry.committed = true
    entry.result = { ...entry.result, status: 'COMMITTED' }
    importState.committedFileNames.add(entry.fileName)
    const result: ImportCommitResult = {
      batchId: entry.result.batchId,
      status: 'COMMITTED',
      successRows: entry.result.successRows,
      failRows: 0,
    }
    return ok(result)
  }),

  /* ------------------------------ 报表 ------------------------------ */

  http.get(`${API}/reports/monthly`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const search = new URL(request.url).searchParams
    const periodYm = search.get('periodYm')
    if (!periodYm) return fail(400, 'periodYm 不能为空')
    const companyId = search.get('companyId') ?? DEMO_USER.companyId
    const deptId = search.get('deptId')
    // 模拟 v1.2 口径：只有 LOCKED/ARCHIVED 期间计入统计；未锁定的期间进入 excludedPeriods
    const period = state.periods.find(
      (item) => item.periodYm === periodYm && item.companyId === companyId,
    )
    const counted = period !== undefined && (period.status === 'LOCKED' || period.status === 'ARCHIVED')
    const rows = counted
      ? MONTHLY_REPORT_ROWS.filter((row) => row.periodYm === periodYm)
          .filter((row) => row.companyId === companyId)
          .filter((row) => (deptId ? row.deptId === deptId : true))
      : []
    const excludedPeriods = period && !counted ? [period.periodYm] : []
    return ok({ rows, excludedPeriods })
  }),

  http.get(`${API}/reports/annual`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const search = new URL(request.url).searchParams
    const year = search.get('year')
    const groupBy = search.get('groupBy')
    const companyId = search.get('companyId')
    if (!year) return fail(400, 'year 不能为空')
    if (!groupBy) return fail(400, 'groupBy 不能为空')
    if (groupBy === 'DEPT' && currentUserOf(request)?.dataScope === 'ALL' && !companyId) {
      return fail(400, '集团范围按部门汇总必须提供 companyId')
    }
    const rows = (groupBy === 'EMP' ? ANNUAL_REPORT_EMP_ROWS : ANNUAL_REPORT_ROWS).filter(
      (row) => row.year === year,
    )
    const excludedPeriods = state.periods
      .filter((period) => period.periodYm.startsWith(`${year}-`))
      .filter((period) => period.status === 'DRAFT' || period.status === 'EDITING')
      .map((period) => period.periodYm)
      .sort()
    return ok({ rows, excludedPeriods })
  }),

  http.post(`${API}/reports/export`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'payroll:export')) return fail(403, '无权限执行该操作')
    const body = (await request.json()) as ExportRequest
    if (!body?.reportType || !body.params || !body.format) {
      return fail(400, '缺少 reportType / params / format')
    }
    const params = body.params
    if (body.reportType === 'ANNUAL') {
      if (!params.year || !params.groupBy) return fail(400, '年度报表必须提供 params.year 与 params.groupBy')
      if (params.groupBy === 'DEPT' && currentUserOf(request)?.dataScope === 'ALL' && !params.companyId) {
        return fail(400, '集团范围按部门导出必须提供 params.companyId')
      }
    } else if (!params.periodYm) {
      return fail(400, '该报表类型必须提供 params.periodYm')
    }

    const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)
    const filename = `${body.reportType.toLowerCase()}-${params.periodYm ?? params.year}-${stamp}.xlsx`
    const blob = new Blob([`PK\u0003\u0004 mock export ${body.reportType}`], { type: XLSX_MIME })
    return new HttpResponse(blob, {
      status: 200,
      headers: { 'Content-Type': XLSX_MIME, 'Content-Disposition': `attachment; filename="${filename}"` },
    })
  }),

  /* ------------------------------ 系统管理 ------------------------------ */

  /* ------------------------------ 系统参数 ------------------------------ */

  http.get(`${API}/configs`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:config:view')) return fail(403, '无权限执行该操作')
    return ok(state.configs)
  }),

  http.put(`${API}/configs/:key`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:config:edit')) return fail(403, '无权限执行该操作')
    const config = state.configs.find((item: SystemConfig) => item.key === params.key)
    if (!config) return fail(400, '参数不存在')
    if (!config.editable) return fail(409, '该参数由系统维护，只读')
    const body = (await request.json()) as { value?: unknown }
    if (typeof body?.value !== 'string') return fail(400, 'value 必须是字符串')
    config.value = body.value
    persistConfigs()
    return ok(config)
  }),

  http.get(`${API}/permissions`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    return ok(PERMISSIONS)
  }),

  http.get(`${API}/users`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const search = new URL(request.url).searchParams
    const current = Math.max(1, Number(search.get('current') ?? '1'))
    const size = Math.max(1, Number(search.get('size') ?? '20'))
    const keyword = (search.get('keyword') ?? '').trim().toLowerCase()
    const companyId = search.get('companyId')
    const deptId = search.get('deptId')
    const status = search.get('status')

    const list = state.users
      .map(presentUser)
      .filter((user) => {
        if (keyword && !`${user.username}${user.realName}`.toLowerCase().includes(keyword)) return false
        if (companyId && user.companyId !== companyId) return false
        if (deptId && user.deptId !== deptId) return false
        if (status && String(user.status) !== status) return false
        return true
      })
      .sort((a, b) => a.username.localeCompare(b.username))

    const total = list.length
    const start = (current - 1) * size
    return ok({ records: list.slice(start, start + size), total, current, size })
  }),

  http.post(`${API}/users`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:user:edit')) return fail(403, '无权限执行该操作')
    const body = (await request.json()) as UserCreateRequest
    if (!body.companyId || !body.username || !body.password || !body.realName) {
      return fail(400, '主体、用户名、密码、姓名为必填')
    }
    if (body.password.length < 8) return fail(400, '密码至少 8 位')
    if (state.users.some((user) => user.username === body.username)) return fail(409, '用户名已存在')
    if (body.employeeId && state.users.some((user) => user.employeeId === body.employeeId)) {
      return fail(409, '该人员已关联其他账号')
    }
    const user: User = {
      id: String(state.nextUserId++),
      companyId: body.companyId,
      username: body.username,
      realName: body.realName,
      employeeId: body.employeeId ?? null,
      deptId: body.deptId ?? null,
      mobileMask: null,
      status: body.status ?? 1,
      lastLoginAt: null,
      roleIds: body.roleIds ?? [],
      permissions: [],
      dataScope: 'SELF',
    }
    state.users.push(user)
    return ok(presentUser(user))
  }),

  http.put(`${API}/users/:id`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:user:edit')) return fail(403, '无权限执行该操作')
    const user = state.users.find((item) => item.id === params.id)
    if (!user) return fail(400, '用户不存在')
    const body = (await request.json()) as UserUpdateRequest
    if (body.status === 0 && user.id === currentUserOf(request)?.userId) return fail(409, '不能停用自己')
    if (body.employeeId && state.users.some((item) => item.id !== user.id && item.employeeId === body.employeeId)) {
      return fail(409, '该人员已关联其他账号')
    }
    Object.assign(user, {
      ...(body.realName !== undefined ? { realName: body.realName } : {}),
      ...(body.employeeId !== undefined ? { employeeId: body.employeeId } : {}),
      ...(body.deptId !== undefined ? { deptId: body.deptId } : {}),
      ...(body.status !== undefined ? { status: body.status } : {}),
      ...(body.roleIds !== undefined ? { roleIds: body.roleIds } : {}),
    })
    return ok(presentUser(user))
  }),

  http.delete(`${API}/users/:id`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:user:edit')) return fail(403, '无权限执行该操作')
    if (String(params.id) === currentUserOf(request)?.userId) return fail(409, '不能删除自己')
    const index = state.users.findIndex((item) => item.id === params.id)
    if (index < 0) return fail(400, '用户不存在')
    state.users.splice(index, 1)
    return ok(null)
  }),

  http.post(`${API}/users/:id/reset-password`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:user:edit')) return fail(403, '无权限执行该操作')
    const user = state.users.find((item) => item.id === params.id)
    if (!user) return fail(400, '用户不存在')
    const body = (await request.json()) as { newPassword?: string }
    if (!body.newPassword || body.newPassword.length < 8) return fail(400, '新密码至少 8 位')
    return ok(presentUser(user))
  }),

  http.get(`${API}/roles`, ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    const search = new URL(request.url).searchParams
    const companyId = search.get('companyId')
    const status = search.get('status')
    return ok(
      state.roles
        .filter((role) => (companyId ? role.companyId === companyId : true))
        .filter((role) => (status !== null ? String(role.status) === status : true)),
    )
  }),

  http.post(`${API}/roles`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:role:edit')) return fail(403, '无权限执行该操作')
    const body = (await request.json()) as RoleCreateRequest
    if (!body.companyId || !body.roleCode || !body.roleName || !body.dataScope) {
      return fail(400, '主体、角色编码、名称、数据范围为必填')
    }
    if (state.roles.some((role) => role.companyId === body.companyId && role.roleCode === body.roleCode)) {
      return fail(409, '角色编码已存在')
    }
    if (body.dataScope === 'ALL' && !hasPermission(request, 'sys:role:grant:all')) {
      return fail(403, '授予集团数据范围需要 sys:role:grant:all')
    }
    const role = {
      id: String(state.nextRoleId++),
      companyId: body.companyId,
      roleCode: body.roleCode,
      roleName: body.roleName,
      dataScope: body.dataScope,
      remark: body.remark ?? null,
      status: body.status ?? 1,
      permissions: body.permissions ?? [],
    }
    state.roles.push(role)
    return ok(role)
  }),

  http.put(`${API}/roles/:id`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:role:edit')) return fail(403, '无权限执行该操作')
    const role = state.roles.find((item) => item.id === params.id)
    if (!role) return fail(400, '角色不存在')
    const body = (await request.json()) as RoleUpdateRequest
    if (body.dataScope === 'ALL' && !hasPermission(request, 'sys:role:grant:all')) {
      return fail(403, '授予集团数据范围需要 sys:role:grant:all')
    }
    Object.assign(role, {
      ...(body.roleCode !== undefined ? { roleCode: body.roleCode } : {}),
      ...(body.roleName !== undefined ? { roleName: body.roleName } : {}),
      ...(body.dataScope !== undefined ? { dataScope: body.dataScope } : {}),
      ...(body.remark !== undefined ? { remark: body.remark } : {}),
      ...(body.status !== undefined ? { status: body.status } : {}),
    })
    return ok(role)
  }),

  http.delete(`${API}/roles/:id`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:role:edit')) return fail(403, '无权限执行该操作')
    const occupied = state.users.some((user) => user.roleIds.includes(String(params.id)))
    if (occupied) return fail(409, '仍有用户关联该角色，请先改授其它角色')
    const index = state.roles.findIndex((item) => item.id === params.id)
    if (index < 0) return fail(400, '角色不存在')
    state.roles.splice(index, 1)
    return ok(null)
  }),

  http.put(`${API}/roles/:id/grants`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:role:edit')) return fail(403, '无权限执行该操作')
    const role = state.roles.find((item) => item.id === params.id)
    if (!role) return fail(400, '角色不存在')
    const body = (await request.json()) as { permissions?: string[] }
    if (!Array.isArray(body.permissions)) return fail(400, 'permissions 必须是数组')
    const outside = body.permissions.filter((code) => !hasPermission(request, code))
    if (outside.length > 0) return fail(403, `不得授予操作者未持有的权限点：${outside.join(', ')}`)
    role.permissions = [...body.permissions] as Role['permissions']
    return ok(role)
  }),

  http.post(`${API}/depts`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:dept:edit')) return fail(403, '无权限执行该操作')
    const body = (await request.json()) as DeptCreateRequest
    if (!body.companyId || !body.deptCode || !body.deptName) return fail(400, '主体、部门编码、名称为必填')
    if (state.depts.some((dept) => dept.companyId === body.companyId && dept.deptCode === body.deptCode)) {
      return fail(409, '部门编码已存在')
    }
    const parentId = body.parentId ?? '0'
    const dept = {
      id: String(state.nextDeptId++),
      companyId: body.companyId,
      parentId,
      deptCode: body.deptCode,
      deptName: body.deptName,
      ancestors: ancestorsFor(parentId),
      sortNo: body.sortNo ?? 0,
      status: body.status ?? 1,
    }
    state.depts.push(dept)
    return ok(dept)
  }),

  http.put(`${API}/depts/:id`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:dept:edit')) return fail(403, '无权限执行该操作')
    const dept = state.depts.find((item) => item.id === params.id)
    if (!dept) return fail(400, '部门不存在')
    const body = (await request.json()) as DeptUpdateRequest
    if (body.parentId !== undefined) {
      if (body.parentId === dept.id) return fail(400, '上级部门不能是自己')
      const parent = state.depts.find((item) => item.id === body.parentId)
      if (parent && parent.ancestors.split(',').includes(dept.id)) {
        return fail(400, '上级部门不能是自己的下级')
      }
      dept.parentId = body.parentId
      dept.ancestors = ancestorsFor(body.parentId)
    }
    Object.assign(dept, {
      ...(body.deptCode !== undefined ? { deptCode: body.deptCode } : {}),
      ...(body.deptName !== undefined ? { deptName: body.deptName } : {}),
      ...(body.sortNo !== undefined ? { sortNo: body.sortNo } : {}),
      ...(body.status !== undefined ? { status: body.status } : {}),
    })
    return ok(dept)
  }),

  http.delete(`${API}/depts/:id`, ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:dept:edit')) return fail(403, '无权限执行该操作')
    const id = String(params.id)
    if (state.depts.some((dept) => dept.parentId === id)) return fail(409, '存在下级部门，请先迁移或删除下级')
    if (state.employees.some((employee) => employee.deptId === id)) return fail(409, '部门下存在关联员工')
    if (state.users.some((user) => user.deptId === id)) return fail(409, '部门下存在关联用户')
    const index = state.depts.findIndex((dept) => dept.id === id)
    if (index < 0) return fail(400, '部门不存在')
    state.depts.splice(index, 1)
    return ok(null)
  }),

  http.post(`${API}/companies`, async ({ request }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:company:edit')) return fail(403, '无权限执行该操作')
    const body = (await request.json()) as CompanyCreateRequest
    if (!body.companyCode || !body.companyName) return fail(400, '主体编码与名称为必填')
    if (state.companies.some((company) => company.companyCode === body.companyCode)) {
      return fail(409, '主体编码已存在')
    }
    const company = {
      id: String(state.nextCompanyId++),
      companyCode: body.companyCode,
      companyName: body.companyName,
      status: body.status ?? 1,
    }
    state.companies.push(company)
    return ok(company)
  }),

  http.put(`${API}/companies/:id`, async ({ request, params }) => {
    const denied = requireAuth(request)
    if (denied) return denied
    if (!hasPermission(request, 'sys:company:edit')) return fail(403, '无权限执行该操作')
    const company = state.companies.find((item) => item.id === params.id)
    if (!company) return fail(400, '主体不存在')
    const body = (await request.json()) as CompanyUpdateRequest
    if (
      body.companyCode &&
      state.companies.some((item) => item.id !== company.id && item.companyCode === body.companyCode)
    ) {
      return fail(409, '主体编码已存在')
    }
    Object.assign(company, {
      ...(body.companyCode !== undefined ? { companyCode: body.companyCode } : {}),
      ...(body.companyName !== undefined ? { companyName: body.companyName } : {}),
      ...(body.status !== undefined ? { status: body.status } : {}),
    })
    return ok(company)
  }),
]

const SCOPE_RANK: Record<string, number> = { SELF: 1, DEPT: 2, DEPT_SUB: 3, COMPANY: 4, ALL: 5 }

/** 用户权限点/数据范围由所授角色合并得出（与后端一致）。 */
function presentUser(user: User): User {
  const permissions = new Set<string>()
  const scopes: string[] = []
  for (const role of state.roles) {
    if (!user.roleIds.includes(role.id)) continue
    role.permissions.forEach((permission) => permissions.add(permission))
    scopes.push(role.dataScope)
  }
  scopes.sort((a, b) => (SCOPE_RANK[b] ?? 0) - (SCOPE_RANK[a] ?? 0))
  return {
    ...user,
    permissions: [...permissions] as User['permissions'],
    dataScope: (scopes[0] ?? 'SELF') as User['dataScope'],
  }
}

/** 由上级部门推导 ancestors（根为 parentId=0）。 */
function ancestorsFor(parentId: string): string {
  if (!parentId || parentId === '0') return ',0,'
  const parent = state.depts.find((dept) => dept.id === parentId)
  return parent ? `${parent.ancestors}${parent.id},` : ',0,'
}
