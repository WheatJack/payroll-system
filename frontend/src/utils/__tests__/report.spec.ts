import { describe, expect, test } from 'vitest'
import {
  annualCountLabel,
  annualCountValue,
  formatCount,
  formatExcludedPeriods,
  paginateRows,
  sumCountColumn,
  sumMoneyColumn,
  validateAnnualFilters,
  validateMonthlyFilters,
} from '../report'

describe('报表筛选校验', () => {
  test('月度必须选择合法年月', () => {
    expect(validateMonthlyFilters({})).toBe('请选择工资年月')
    expect(validateMonthlyFilters({ periodYm: '2026-13' })).toBe('请选择工资年月')
    expect(validateMonthlyFilters({ periodYm: '2026-11' })).toBeNull()
  })

  test('年度必须选择年份与汇总维度', () => {
    expect(validateAnnualFilters({ groupBy: 'EMP' }, 'COMPANY')).toBe('请选择年份')
    expect(validateAnnualFilters({ year: '26', groupBy: 'EMP' }, 'COMPANY')).toBe('请选择年份')
    expect(validateAnnualFilters({ year: '2026' }, 'COMPANY')).toBe('请选择汇总维度')
    expect(validateAnnualFilters({ year: '2026', groupBy: 'EMP' }, 'COMPANY')).toBeNull()
  })

  test('集团范围按部门汇总必须选主体；非集团或按人不强制', () => {
    expect(validateAnnualFilters({ year: '2026', groupBy: 'DEPT' }, 'ALL')).toContain('必须选择法人主体')
    expect(validateAnnualFilters({ year: '2026', groupBy: 'DEPT', companyId: '1' }, 'ALL')).toBeNull()
    expect(validateAnnualFilters({ year: '2026', groupBy: 'DEPT' }, 'COMPANY')).toBeNull()
    expect(validateAnnualFilters({ year: '2026', groupBy: 'EMP' }, 'ALL')).toBeNull()
  })
})

describe('报表合计', () => {
  test('金额列用 money 累加，不丢精度', () => {
    const rows = [{ amount: '5517.24' }, { amount: '689.66' }, { amount: '600.00' }]
    expect(sumMoneyColumn(rows, (row) => row.amount)).toBe('6806.90')
    expect(sumMoneyColumn([{ amount: '0.10' }, { amount: '0.20' }], (row) => row.amount)).toBe('0.30')
  })

  test('缺失金额按 0.00 处理，不产生 NaN', () => {
    const rows: Array<{ amount?: string | null }> = [{ amount: '100.00' }, {}, { amount: null }]
    expect(sumMoneyColumn(rows, (row) => row.amount)).toBe('100.00')
  })

  test('空串/非法金额不抛错，按 0.00 计入；千分位能清洗', () => {
    const rows = [{ amount: '' }, { amount: 'abc' }, { amount: '1,234.50' }, { amount: '0.50' }]
    expect(sumMoneyColumn(rows, (row) => row.amount)).toBe('1235.00')
  })

  test('空数组合计为 0.00', () => {
    expect(sumMoneyColumn([], () => '0.00')).toBe('0.00')
  })

  test('金额列混入数字（后端类型漂移）也能累加', () => {
    const rows = [{ amount: 6 }, { amount: '4.00' }] as Array<{ amount: string }>
    expect(sumMoneyColumn(rows, (row) => row.amount)).toBe('10.00')
  })

  test('人数列是整数求和', () => {
    expect(sumCountColumn([{ n: 1 }, { n: 2 }, {}], (row) => row.n)).toBe(3)
  })
})


describe('报表 v1.2 结构（excludedPeriods / 月份数）', () => {
  test('未锁定期间的提示文案', () => {
    expect(formatExcludedPeriods(['2025-01'])).toBe('2025-01 已有工资数据但未锁定，未计入本次统计')
    expect(formatExcludedPeriods(['2025-01', '2025-02'])).toBe('2025-01、2025-02 已有工资数据但未锁定，未计入本次统计')
    expect(formatExcludedPeriods([])).toBeNull()
    expect(formatExcludedPeriods(null)).toBeNull()
    expect(formatExcludedPeriods(['  '])).toBeNull()
  })

  test('年度计数列：按人看 monthCount，按部门看 employeeCount', () => {
    const empRow = { employeeCount: null, monthCount: 12 }
    const deptRow = { employeeCount: 3, monthCount: null }
    expect(annualCountLabel('EMP')).toBe('月份数')
    expect(annualCountLabel('DEPT')).toBe('人数')
    expect(annualCountValue(empRow as never, 'EMP')).toBe(12)
    expect(annualCountValue(empRow as never, 'DEPT')).toBeNull()
    expect(annualCountValue(deptRow as never, 'DEPT')).toBe(3)
    expect(annualCountValue(deptRow as never, 'EMP')).toBeNull()
  })

  test('计数 null/undefined 显示 — 而不是 NaN', () => {
    expect(formatCount(null)).toBe('—')
    expect(formatCount(undefined)).toBe('—')
    expect(formatCount(Number.NaN)).toBe('—')
    expect(formatCount(0)).toBe('0')
    expect(formatCount(12)).toBe('12')
  })
})


describe('年度报表客户端分页（I-7）', () => {
  const rows = Array.from({ length: 120 }, (_, index) => index + 1)

  test('按页切片，末页只取剩余行', () => {
    expect(paginateRows(rows, 1, 50)).toHaveLength(50)
    expect(paginateRows(rows, 1, 50)[0]).toBe(1)
    expect(paginateRows(rows, 3, 50)).toEqual(rows.slice(100))
  })

  test('页码越界自动夹紧到有效范围', () => {
    expect(paginateRows(rows, 99, 50)[0]).toBe(101)
    expect(paginateRows(rows, 0, 50)[0]).toBe(1)
    expect(paginateRows([], 5, 50)).toEqual([])
  })

  test('非法页大小回退为 1，不会返回全部', () => {
    expect(paginateRows(rows, 1, 0)).toHaveLength(1)
  })
})
