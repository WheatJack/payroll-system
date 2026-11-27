/**
 * 报表页的表单校验与合计。
 *
 * 口径提醒（契约 v1.1）：
 * - 数据范围（SELF/DEPT/DEPT_SUB/COMPANY/ALL）与「仅统计 LOCKED/ARCHIVED」都由后端过滤，
 *   前端拿到什么就显示什么，**不要在前端再做范围/状态过滤**。
 * - `employeeCount` 是去重人数，年度值不等于各月相加，不能用「年度 ÷ 12」反推。
 */
import type { AnnualReportRow, DataScope, ReportGroupBy } from '@/api/models'
import { coerce, sum } from './money'

const PERIOD_YM = /^\d{4}-(0[1-9]|1[0-2])$/
const YEAR = /^\d{4}$/

export interface ReportFilterState {
  periodYm?: string
  year?: string
  groupBy?: ReportGroupBy
  companyId?: string
  deptId?: string
}

export function validateMonthlyFilters(filters: ReportFilterState): string | null {
  if (!filters.periodYm || !PERIOD_YM.test(filters.periodYm)) return '请选择工资年月'
  return null
}

/**
 * 年度筛选校验。
 * `groupBy=DEPT` 且操作者数据范围为 `ALL`（集团）时 `companyId` 必填，
 * 否则不同主体的同名部门会被合并加总（契约明确要求 400）。
 */
export function validateAnnualFilters(
  filters: ReportFilterState,
  dataScope: DataScope | undefined,
): string | null {
  if (!filters.year || !YEAR.test(filters.year)) return '请选择年份'
  if (!filters.groupBy) return '请选择汇总维度'
  if (filters.groupBy === 'DEPT' && dataScope === 'ALL' && !filters.companyId) {
    return '集团范围按部门汇总时必须选择法人主体，否则不同主体的同名部门会被合并'
  }
  return null
}

/**
 * 金额列合计：逐项 money.add，不做二次舍入；空集合计为 0.00。
 * 每一格先做兜底（空串/非法/带千分位都能处理），避免后端直出的脏值把整个 computed 抛崩。
 */
export function sumMoneyColumn<T>(rows: readonly T[], pick: (row: T) => string | null | undefined): string {
  return sum(
    rows.map((row) => {
      const value = pick(row)
      if (value === null || value === undefined) return '0.00'
      return coerce(value) ?? '0.00'
    }),
    '0.00',
  )
}

/** 人数列合计（整数，非金额）。 */
export function sumCountColumn<T>(rows: readonly T[], pick: (row: T) => number | null | undefined): number {
  return rows.reduce((total, row) => total + (pick(row) ?? 0), 0)
}


/* ------------------------------ v1.2 报表结构 ------------------------------ */

/**
 * 「有明细但未锁定、未计入统计」的期间提示。
 * 之前这些月份被静默跳过，用户会以为报表算错了，必须显式展示。
 */
export function formatExcludedPeriods(periods: readonly string[] | null | undefined): string | null {
  const list = (periods ?? []).map((period) => period.trim()).filter((period) => period !== '')
  if (list.length === 0) return null
  return `${list.join('、')} 已有工资数据但未锁定，未计入本次统计`
}

/** 年度按人看「月份数」，按部门看「人数」。 */
export function annualCountLabel(groupBy: ReportGroupBy): string {
  return groupBy === 'EMP' ? '月份数' : '人数'
}

/**
 * 年度行的计数列取值：EMP 取 monthCount（可能为 null），DEPT 取 employeeCount。
 * null 一律返回 null（由 formatCount 显示 —），绝不能显示 NaN。
 */
export function annualCountValue(row: AnnualReportRow, groupBy: ReportGroupBy): number | null {
  const value = groupBy === 'EMP' ? row.monthCount : row.employeeCount
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** 计数展示：null/undefined/非数值 → — */
export function formatCount(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '—'
}
