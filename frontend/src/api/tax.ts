import { api } from './client'
import type { AnnualTaxReportResult } from './models'

export interface AnnualTaxQuery {
  year: string
  employeeId?: string
  deptId?: string
}

/**
 * 年度个税核对（仅统计已锁定/已归档期间）。
 * 返回 `{ rows, excludedPeriods }`，与其它报表一致。
 */
export function getAnnualTaxReport(query: AnnualTaxQuery): Promise<AnnualTaxReportResult> {
  return api.get<AnnualTaxReportResult>('/reports/annual-tax', { params: query })
}
