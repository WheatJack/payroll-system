import { describe, expect, test } from 'vitest'
import { sumCountColumn, sumMoneyColumn, validateAnnualFilters, validateMonthlyFilters } from '../report'

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

  test('人数列是整数求和', () => {
    expect(sumCountColumn([{ n: 1 }, { n: 2 }, {}], (row) => row.n)).toBe(3)
  })
})
