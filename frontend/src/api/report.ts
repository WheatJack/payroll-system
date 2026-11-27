import { api } from './client'
import { downloadFile, type DownloadedFile } from './download'
import type {
  AnnualReportResult,
  ExportRequest,
  MonthlyReportResult,
  ReportGroupBy,
} from './models'

export interface MonthlyReportQuery {
  periodYm: string
  companyId?: string
  deptId?: string
}

export interface AnnualReportQuery {
  year: string
  groupBy: ReportGroupBy
  companyId?: string
  deptId?: string
}

/**
 * 月度汇总。`excludedPeriods` 列出「有明细但未锁定、因此未计入统计」的期间，
 * 必须展示给用户（否则会以为报表算错了）。
 */
export function getMonthlyReport(query: MonthlyReportQuery): Promise<MonthlyReportResult> {
  return api.get<MonthlyReportResult>('/reports/monthly', { params: query })
}

/** 年度汇总：按人 / 按部门，同样返回 `{ rows, excludedPeriods }`。 */
export function getAnnualReport(query: AnnualReportQuery): Promise<AnnualReportResult> {
  return api.get<AnnualReportResult>('/reports/annual', { params: query })
}

/** 导出二进制；失败时后端返回 JSON 统一响应包，由 downloadFile 负责区分。 */
export function exportReport(payload: ExportRequest, fallbackFilename = 'report.xlsx'): Promise<DownloadedFile> {
  return downloadFile({ url: '/reports/export', method: 'POST', data: payload }, fallbackFilename)
}
