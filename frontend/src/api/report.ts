import { api } from './client'
import { downloadFile, type DownloadedFile } from './download'
import type { AnnualReportRow, ExportRequest, MonthlyReportRow, ReportGroupBy } from './models'

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

/** 月度汇总：数据范围与统计口径（仅 LOCKED/ARCHIVED）由后端负责，前端不再二次过滤。 */
export function getMonthlyReport(query: MonthlyReportQuery): Promise<MonthlyReportRow[]> {
  return api.get<MonthlyReportRow[]>('/reports/monthly', { params: query })
}

/** 年度汇总：按人 / 按部门。 */
export function getAnnualReport(query: AnnualReportQuery): Promise<AnnualReportRow[]> {
  return api.get<AnnualReportRow[]>('/reports/annual', { params: query })
}

/** 导出二进制；失败时后端返回 JSON 统一响应包，由 downloadFile 负责区分。 */
export function exportReport(payload: ExportRequest, fallbackFilename = 'report.xlsx'): Promise<DownloadedFile> {
  return downloadFile({ url: '/reports/export', method: 'POST', data: payload }, fallbackFilename)
}
