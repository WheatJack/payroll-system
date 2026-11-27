/**
 * Mock 数据源（Task 17）。
 *
 * 契约响应示例直接复用 `docs/api/examples/*.json`（通过 ?raw 读取，不复制粘贴），
 * 其余端点按 `docs/api/openapi.yaml` 的 schema 构造最小可用数据。
 */
import gridExampleRaw from './fixtures/grid-response.json?raw'
import type { SystemConfig } from '@/api/config'
import type { AnnualTaxRow, InsuranceParam, SocialInsurance, SpecialDeduction } from '@/api/models'
import type {
  Company,
  CompProfile,
  CurrentUser,
  Dept,
  Employee,
  GridResponse,
  PayItem,
  Period,
  PermissionItem,
  Role,
  User,
} from '@/api/models'

interface Envelope<T> {
  code: number
  msg: string
  data: T
}

/** GET /periods/{id}/grid 的 mock 响应 = 冻结契约的示例响应（截断示例：2 列 1 行）。 */
export const GRID_EXAMPLE = (JSON.parse(gridExampleRaw) as Envelope<GridResponse>).data

export const DEMO_TOKEN = 'mock-payroll-hmac-token'

export const DEMO_USER: CurrentUser = {
  userId: '1',
  username: 'hr',
  realName: '李人事',
  companyId: '1',
  employeeId: null,
  // 与角色 ADMIN(ALL) 保持一致：mock 用户的数据范围由所授角色合并得出
  dataScope: 'ALL',
  permissions: [
    'payroll:record:view',
    'payroll:record:edit',
    'payroll:period:lock',
    'payroll:period:unlock',
    'payroll:import:submit',
    'payroll:export',
    'payroll:sensitive:view',
    'sys:user:view',
    'sys:user:edit',
    'sys:role:view',
    'sys:role:edit',
    'sys:role:grant:all',
    'sys:dept:edit',
    'sys:company:edit',
    'sys:config:view',
    'sys:config:edit',
  ],
}

/** 只读演示用户：只持 payroll:record:view，用于验证权限分支（导出隐藏 / 导入禁用）。 */
export const VIEWER_USER: CurrentUser = {
  userId: '9',
  username: 'viewer',
  realName: '只读用户',
  companyId: '1',
  employeeId: null,
  dataScope: 'COMPANY',
  permissions: ['payroll:record:view'],
}

/** token -> 当前用户（多用户 mock，便于验证权限分支）。 */
export const DEMO_TOKENS: Record<string, CurrentUser> = {
  [DEMO_TOKEN]: DEMO_USER,
  'mock-viewer-token': VIEWER_USER,
}

export const COMPANIES: Company[] = [
  { id: '1', companyCode: 'BJ', companyName: '北京总部', status: 1 },
  { id: '2', companyCode: 'SH', companyName: '上海分公司', status: 1 },
]

export const DEPTS: Dept[] = [
  { id: '1', companyId: '1', parentId: '0', deptCode: 'RD', deptName: '北京研发部', ancestors: ',0,', sortNo: 10, status: 1 },
  { id: '2', companyId: '1', parentId: '0', deptCode: 'FIN', deptName: '北京财务部', ancestors: ',0,', sortNo: 20, status: 1 },
  { id: '3', companyId: '1', parentId: '1', deptCode: 'RD1', deptName: '研发一组', ancestors: ',0,1,', sortNo: 11, status: 1 },
  { id: '10', companyId: '2', parentId: '0', deptCode: 'SHFIN', deptName: '上海财务部', ancestors: ',0,', sortNo: 10, status: 1 },
]

export const EMPLOYEES: Employee[] = [
  {
    id: '1',
    companyId: '1',
    empNo: 'E001',
    name: '张三',
    gender: 1,
    idType: 'ID_CARD',
    idNoMask: '****1234',
    mobileMask: '138****8000',
    employeeType: 'REGULAR',
    jobLevel: 'P6',
    positionName: '高级工程师',
    deptId: '1',
    deptName: '北京研发部',
    hireDate: '2020-03-01',
    workStartDate: '2019-07-01',
    seniorityYears: '6.00',
    socialSeniorityYears: '7.40',
    leaveDate: null,
    status: 1,
    remark: null,
  },
  {
    id: '2',
    companyId: '1',
    empNo: 'E002',
    name: '李四',
    gender: 2,
    idType: 'ID_CARD',
    idNoMask: '****5678',
    mobileMask: '139****9000',
    employeeType: 'PROBATION',
    jobLevel: 'P5',
    positionName: '财务专员',
    deptId: '2',
    deptName: '北京财务部',
    hireDate: '2026-09-01',
    workStartDate: '2026-09-01',
    seniorityYears: '0.25',
    socialSeniorityYears: '0.25',
    leaveDate: null,
    status: 1,
    remark: null,
  },
  {
    id: '3',
    companyId: '1',
    empNo: 'E003',
    name: '王五',
    gender: 1,
    idType: 'ID_CARD',
    idNoMask: '****9012',
    mobileMask: null,
    employeeType: 'INTERN',
    jobLevel: null,
    positionName: '实习生',
    deptId: '3',
    deptName: '研发一组',
    hireDate: '2026-07-01',
    workStartDate: '2026-07-01',
    seniorityYears: '0.40',
    socialSeniorityYears: '0.40',
    leaveDate: null,
    status: 1,
    remark: null,
  },
]

/** 明文证件号只存在于 mock 内部，用于演示 409 查重；接口永远只回掩码。 */
/** 明文手机号（mock 内部用，接口只回掩码或按权限回明文） */
export const EMPLOYEE_MOBILES: Record<string, string> = {
  '1': '13800008000',
  '2': '13900009000',
  '3': '13700001000',
}

export const EMPLOYEE_ID_NOS: Record<string, string> = {
  '1': '110101199001011234',
  '2': '310101199505055678',
  '3': '440101200301019012',
}

/** 主体 1 的工资项（含固定/变动/计算，方向 1 增加 / 2 扣款）。 */
export const PAY_ITEMS: PayItem[] = [
  { id: '1', companyId: '0', itemCode: 'BASE_SALARY', itemName: '基本工资', category: 'FIXED', direction: 1, dataType: 'AMOUNT', precisionScale: 2, includeInGross: true, editable: false, importable: true, sortNo: 10, status: 1 },
  { id: '2', companyId: '0', itemCode: 'SENIORITY_PAY', itemName: '工龄工资', category: 'FIXED', direction: 1, dataType: 'AMOUNT', precisionScale: 2, includeInGross: true, editable: false, importable: false, sortNo: 20, status: 1 },
  { id: '3', companyId: '0', itemCode: 'POST_ALLOW', itemName: '岗位津贴', category: 'FIXED', direction: 1, dataType: 'AMOUNT', precisionScale: 2, includeInGross: true, editable: true, importable: true, sortNo: 30, status: 1 },
  { id: '4', companyId: '0', itemCode: 'MEAL_ALLOW', itemName: '餐补', category: 'FIXED', direction: 1, dataType: 'AMOUNT', precisionScale: 2, includeInGross: true, editable: true, importable: true, sortNo: 40, status: 1 },
  { id: '5', companyId: '0', itemCode: 'TRANSPORT_ALLOW', itemName: '交通补贴', category: 'FIXED', direction: 1, dataType: 'AMOUNT', precisionScale: 2, includeInGross: true, editable: true, importable: true, sortNo: 50, status: 1 },
  { id: '6', companyId: '1', itemCode: 'PERF_BONUS', itemName: '绩效奖金', category: 'VARIABLE', direction: 1, dataType: 'AMOUNT', precisionScale: 2, includeInGross: true, editable: true, importable: true, sortNo: 60, status: 1 },
  { id: '7', companyId: '1', itemCode: 'OVERTIME_PAY', itemName: '加班费', category: 'VARIABLE', direction: 1, dataType: 'AMOUNT', precisionScale: 2, includeInGross: true, editable: true, importable: true, sortNo: 70, status: 1 },
  { id: '8', companyId: '0', itemCode: 'SOCIAL_INSURANCE', itemName: '社保个人部分', category: 'FIXED', direction: 2, dataType: 'AMOUNT', precisionScale: 2, includeInGross: false, editable: false, importable: false, sortNo: 80, status: 1 },
  { id: '9', companyId: '0', itemCode: 'HOUSING_FUND', itemName: '公积金个人部分', category: 'FIXED', direction: 2, dataType: 'AMOUNT', precisionScale: 2, includeInGross: false, editable: false, importable: false, sortNo: 90, status: 1 },
  { id: '10', companyId: '0', itemCode: 'IIT', itemName: '个人所得税', category: 'COMPUTED', direction: 2, dataType: 'AMOUNT', precisionScale: 2, includeInGross: false, editable: false, importable: false, sortNo: 100, status: 1 },
  { id: '11', companyId: '1', itemCode: 'OTHER_DEDUCT', itemName: '其他扣款', category: 'VARIABLE', direction: 2, dataType: 'AMOUNT', precisionScale: 2, includeInGross: false, editable: true, importable: true, sortNo: 110, status: 1 },
  { id: '12', companyId: '0', itemCode: 'ADJ_ROUNDING', itemName: '尾差调整', category: 'COMPUTED', direction: 1, dataType: 'AMOUNT', precisionScale: 2, includeInGross: true, editable: false, importable: false, sortNo: 120, status: 0 },
]

export const PERIODS: Period[] = [
  {
    id: '3',
    companyId: '1',
    periodYm: '2026-11',
    status: 'EDITING',
    employeeCount: 1,
    totalGross: '8600.00',
    totalNet: '8600.00',
    hpd: '21.75',
  },
  {
    id: '2',
    companyId: '1',
    periodYm: '2026-10',
    status: 'LOCKED',
    employeeCount: 1,
    totalGross: '8500.00',
    totalNet: '8500.00',
    hpd: '21.75',
  },
  {
    id: '1',
    companyId: '1',
    periodYm: '2026-09',
    status: 'DRAFT',
    employeeCount: 0,
    totalGross: '0.00',
    totalNet: '0.00',
    hpd: '21.75',
  },
]

export const COMP_PROFILES: CompProfile[] = [
  {
    id: '1',
    employeeId: '1',
    effectiveFrom: '2025-07-01',
    effectiveTo: null,
    baseSalary: '8000.00',
    changeReason: '年度调薪',
    status: 1,
    items: [
      { itemCode: 'POST_ALLOW', amount: '500.00' },
      { itemCode: 'MEAL_ALLOW', amount: '300.00' },
    ],
  },
  {
    id: '2',
    employeeId: '1',
    effectiveFrom: '2024-01-01',
    effectiveTo: '2025-06-30',
    baseSalary: '7200.00',
    changeReason: null,
    status: 1,
    items: [{ itemCode: 'POST_ALLOW', amount: '400.00' }],
  },
]

/** 深拷贝，避免 handler 之间共享引用。 */
export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

/* ------------------------- 报表（M1.5） ------------------------- */

export const MONTHLY_REPORT_ROWS = [
  { companyId: '1', periodYm: '2026-11', deptId: '1', deptName: '北京研发部', employeeCount: 1, grossAmount: '8600.00', deductionAmount: '2150.00', netAmount: '6450.00' },
  { companyId: '1', periodYm: '2026-11', deptId: '2', deptName: '北京财务部', employeeCount: 1, grossAmount: '7200.00', deductionAmount: '1800.00', netAmount: '5400.00' },
  { companyId: '1', periodYm: '2026-10', deptId: '1', deptName: '北京研发部', employeeCount: 1, grossAmount: '8500.00', deductionAmount: '2100.00', netAmount: '6400.00' },
  { companyId: '1', periodYm: '2026-11', deptId: '3', deptName: '研发一组', employeeCount: 1, grossAmount: '3100.00', deductionAmount: '620.00', netAmount: '2480.00' },
]

export const ANNUAL_REPORT_ROWS = [
  { year: '2026', groupBy: 'DEPT', deptId: '1', deptName: '北京研发部', employeeCount: 2, monthCount: null, grossAmount: '17100.00', deductionAmount: '4250.00', netAmount: '12850.00' },
  { year: '2026', groupBy: 'DEPT', deptId: '2', deptName: '北京财务部', employeeCount: 1, monthCount: null, grossAmount: '7200.00', deductionAmount: '1800.00', netAmount: '5400.00' },
]

export const ANNUAL_REPORT_EMP_ROWS = [
  // 按人分组：employeeCount 为 null，月份数见 monthCount（v1.2 语义）
  { year: '2026', groupBy: 'EMP', employeeId: '1', empNo: 'E001', empName: '张三', employeeCount: null, monthCount: 12, grossAmount: '8600.00', deductionAmount: '2150.00', netAmount: '6450.00' },
  { year: '2026', groupBy: 'EMP', employeeId: '2', empNo: 'E002', empName: '李四', employeeCount: null, monthCount: 6, grossAmount: '7200.00', deductionAmount: '1800.00', netAmount: '5400.00' },
]

/* ------------------------- 系统管理（M1.5） ------------------------- */

export const PERMISSIONS: PermissionItem[] = [
  { code: 'payroll:record:view', name: '查看核算', module: 'PAYROLL' },
  { code: 'payroll:record:edit', name: '编制/编辑', module: 'PAYROLL' },
  { code: 'payroll:period:lock', name: '锁定期间', module: 'PAYROLL' },
  { code: 'payroll:period:unlock', name: '解锁期间（高危）', module: 'PAYROLL' },
  { code: 'payroll:import:submit', name: '导入提交', module: 'PAYROLL' },
  { code: 'payroll:export', name: '导出（高危）', module: 'PAYROLL' },
  { code: 'payroll:sensitive:view', name: '查看完整证件号/手机号（高危）', module: 'PAYROLL' },
  { code: 'sys:user:view', name: '查看用户', module: 'SYS' },
  { code: 'sys:user:edit', name: '维护用户', module: 'SYS' },
  { code: 'sys:role:view', name: '查看角色', module: 'SYS' },
  { code: 'sys:role:edit', name: '维护角色', module: 'SYS' },
  { code: 'sys:role:grant:all', name: '授予集团数据范围', module: 'SYS' },
  { code: 'sys:dept:edit', name: '维护部门', module: 'SYS' },
  { code: 'sys:company:edit', name: '维护法人主体', module: 'SYS' },
]

export const ROLES: Role[] = [
  {
    id: '1',
    companyId: '1',
    roleCode: 'ADMIN',
    roleName: '系统管理员',
    dataScope: 'ALL',
    remark: '集团管理员',
    status: 1,
    permissions: [...DEMO_USER.permissions] as Role['permissions'],
  },
  {
    id: '2',
    companyId: '1',
    roleCode: 'HR',
    roleName: '人事专员',
    dataScope: 'COMPANY',
    remark: null,
    status: 1,
    permissions: ['payroll:record:view', 'payroll:record:edit', 'payroll:period:lock', 'payroll:import:submit'] as Role['permissions'],
  },
  {
    id: '3',
    companyId: '1',
    roleCode: 'AUDITOR',
    roleName: '审计只读',
    dataScope: 'COMPANY',
    remark: null,
    status: 1,
    permissions: ['payroll:record:view', 'payroll:export'] as Role['permissions'],
  },
]

export const USERS: User[] = [
  {
    id: '1',
    companyId: '1',
    username: 'hr',
    realName: '李人事',
    employeeId: null,
    deptId: '1',
    mobileMask: '138****8000',
    status: 1,
    lastLoginAt: '2026-11-26T09:00:00Z',
    roleIds: ['1'],
    permissions: [...DEMO_USER.permissions] as User['permissions'],
    dataScope: 'COMPANY',
  },
  {
    id: '2',
    companyId: '1',
    username: 'zhangfy',
    realName: '张芳',
    employeeId: null,
    deptId: '2',
    mobileMask: '139****9000',
    status: 1,
    lastLoginAt: null,
    roleIds: ['2'],
    permissions: ['payroll:record:view', 'payroll:record:edit', 'payroll:period:lock', 'payroll:import:submit'] as Role['permissions'],
    dataScope: 'COMPANY',
  },
  {
    id: '3',
    companyId: '1',
    username: 'audit',
    realName: '王审计',
    employeeId: null,
    deptId: null,
    mobileMask: null,
    status: 0,
    lastLoginAt: null,
    roleIds: ['3'],
    permissions: ['payroll:record:view', 'payroll:export'] as Role['permissions'],
    dataScope: 'COMPANY',
  },
]


/* ------------------------- 系统参数（sys_config） ------------------------- */

export const CONFIGS: SystemConfig[] = [
  {
    key: 'payroll.monthly-payable-days',
    value: '21.75',
    valueType: 'DECIMAL',
    description: '月平均计薪天数',
    editable: true,
    warning: null,
  },
  {
    key: 'payroll.seniority-pay-per-year',
    value: '100',
    valueType: 'DECIMAL',
    description: '工龄工资每年金额',
    editable: true,
    warning: null,
  },
  {
    key: 'payroll.calendar.holidays',
    value: '',
    valueType: 'DATE_LIST',
    description: '法定节假日（用于计薪天数）',
    editable: true,
    warning: '未配置法定节假日，不足月折算只排除周末',
  },
  {
    key: 'payroll.payslip-footer',
    value: '本公司工资保密，请勿外传',
    valueType: 'STRING',
    description: '工资条页脚文案',
    editable: false,
    warning: null,
  },
]



/* ------------------------- 个税社保（M2.5） ------------------------- */

export const SOCIAL_INSURANCE: SocialInsurance[] = [
  {
    id: '1',
    employeeId: '1',
    cityCode: 'BJ',
    // socialBase 省略 = 按近 12 期平均应发自动计算
    housingFundRate: '0.12',
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
  },
  {
    id: '2',
    employeeId: '2',
    cityCode: 'SH',
    socialBase: '8000.00',
    housingFundRate: '0.07',
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
  },
]

export const INSURANCE_PARAMS: Record<string, InsuranceParam[]> = {
  BJ: [
    { insuranceType: 'PENSION', personalRate: '0.08', baseMin: '6821.00', baseMax: '35283.00', extraAmount: '0.00' },
    { insuranceType: 'MEDICAL', personalRate: '0.02', baseMin: '6821.00', baseMax: '35283.00', extraAmount: '3.00' },
    { insuranceType: 'UNEMPLOYMENT', personalRate: '0.005', baseMin: '6821.00', baseMax: '35283.00', extraAmount: '0.00' },
    { insuranceType: 'INJURY', personalRate: '0', baseMin: '0.00', baseMax: '0.00', extraAmount: '0.00' },
    { insuranceType: 'MATERNITY', personalRate: '0', baseMin: '0.00', baseMax: '0.00', extraAmount: '0.00' },
    { insuranceType: 'HOUSING_FUND', personalRate: '0.12', baseMin: '2420.00', baseMax: '35283.00', extraAmount: '0.00' },
  ],
  SH: [
    { insuranceType: 'PENSION', personalRate: '0.08', baseMin: '7384.00', baseMax: '36921.00', extraAmount: '0.00' },
    { insuranceType: 'MEDICAL', personalRate: '0.02', baseMin: '7384.00', baseMax: '36921.00', extraAmount: '0.00' },
    { insuranceType: 'UNEMPLOYMENT', personalRate: '0.005', baseMin: '7384.00', baseMax: '36921.00', extraAmount: '0.00' },
    { insuranceType: 'HOUSING_FUND', personalRate: '0.07', baseMin: '2690.00', baseMax: '36921.00', extraAmount: '0.00' },
  ],
  SZ: [
    { insuranceType: 'PENSION', personalRate: '0.08', baseMin: '2360.00', baseMax: '27927.00', extraAmount: '0.00' },
    { insuranceType: 'MEDICAL', personalRate: '0.02', baseMin: '7778.00', baseMax: '38892.00', extraAmount: '0.00' },
    { insuranceType: 'UNEMPLOYMENT', personalRate: '0.003', baseMin: '2360.00', baseMax: '27927.00', extraAmount: '0.00' },
    { insuranceType: 'HOUSING_FUND', personalRate: '0.05', baseMin: '2360.00', baseMax: '38892.00', extraAmount: '0.00' },
  ],
}

export const SPECIAL_DEDUCTIONS: SpecialDeduction[] = [
  {
    id: '1',
    employeeId: '1',
    deductionType: 'CHILD_EDU',
    monthlyAmount: '2000.00',
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    remark: null,
  },
  {
    id: '2',
    employeeId: '1',
    deductionType: 'HOUSING_LOAN',
    monthlyAmount: '1000.00',
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    remark: '首套房贷',
  },
]

export const ANNUAL_TAX_ROWS: AnnualTaxRow[] = [
  {
    empNo: 'E001',
    empName: '张三',
    deptName: '北京研发部',
    paidMonths: 12,
    cumIncome: '120000.00',
    cumBasicDeduction: '60000.00',
    cumSocial: '9600.00',
    cumFund: '14400.00',
    cumSpecial: '24000.00',
    taxable: '12000.00',
    cumTax: '360.00',
    lastTax: '30.00',
    effectiveRate: '0.0030',
  },
  {
    empNo: 'E002',
    empName: '李四',
    deptName: '北京财务部',
    paidMonths: 6,
    cumIncome: '48000.00',
    cumBasicDeduction: '30000.00',
    cumSocial: '4800.00',
    cumFund: '2400.00',
    cumSpecial: '0.00',
    taxable: '10800.00',
    cumTax: '324.00',
    lastTax: '54.00',
    effectiveRate: '0.0068',
  },
]
