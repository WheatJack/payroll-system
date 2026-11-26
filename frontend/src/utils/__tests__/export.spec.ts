import { describe, expect, test } from 'vitest'
import { buildAnnualParams, buildMonthlyParams, buildPayslipParams, buildPeriodSheetParams } from '../export'

describe('导出参数构造', () => {
  test('月度：只带非空可选字段', () => {
    expect(buildMonthlyParams('2026-11')).toEqual({ periodYm: '2026-11' })
    expect(buildMonthlyParams('2026-11', '1', '2')).toEqual({ periodYm: '2026-11', companyId: '1', deptId: '2' })
  })

  test('年度：year + groupBy 必备，主体/部门可选', () => {
    expect(buildAnnualParams('2026', 'EMP')).toEqual({ year: '2026', groupBy: 'EMP' })
    expect(buildAnnualParams('2026', 'DEPT', '1')).toEqual({ year: '2026', groupBy: 'DEPT', companyId: '1' })
  })

  test('期间工资表：deptId 为空时不出现该键', () => {
    expect(buildPeriodSheetParams('2026-11')).toEqual({ periodYm: '2026-11' })
    expect(buildPeriodSheetParams('2026-11', '3')).toEqual({ periodYm: '2026-11', deptId: '3' })
    expect(Object.keys(buildPeriodSheetParams('2026-11'))).not.toContain('deptId')
  })

  test('工资条：无人选时不传 employeeIds（按数据范围导出）；有人选时传副本', () => {
    expect(buildPayslipParams('2026-11', [])).toEqual({ periodYm: '2026-11' })

    const ids = ['1', '2']
    const params = buildPayslipParams('2026-11', ids)
    expect(params).toEqual({ periodYm: '2026-11', employeeIds: ['1', '2'] })
    expect(params.employeeIds).not.toBe(ids)
  })

  test('序列化后不出现 undefined 字段', () => {
    const json = JSON.stringify(buildPeriodSheetParams('2026-11'))
    expect(json).toBe('{"periodYm":"2026-11"}')
  })
})
