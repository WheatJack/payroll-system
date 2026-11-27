import { api } from './client'
import { downloadFile, type DownloadedFile } from './download'
import type {
  AnnualReportResult,
  AnnualReportRow,
  ExportRequest,
  MonthlyReportResult,
  MonthlyReportRow,
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

export interface ReportResult<T> {
  rows: T[]
  excludedPeriods: string[]
}

/**
 * v1.2 响应是 `{ rows, excludedPeriods }`；滚动发布期间旧后端仍可能返回数组。
 * 这里做一次**无 any** 的运行时分流：数组 → rows，对象 → 原样取字段。
 * 契约类型仍是唯一依据，这只是发布过渡期的兼容层。
 */
export function normalizeReportResult<T>(
  data: ReportResult<T> | readonly T[] | null | undefined,
): ReportResult<T> {
  if (Array.isArray(data)) {
    return { rows: [...(data as readonly T[])], excludedPeriods: [] }
  }
  const result = data as ReportResult<T> | null | undefined
  return {
    rows: Array.isArray(result?.rows) ? result!.rows : [],
    excludedPeriods: Array.isArray(result?.excludedPeriods) ? result!.excludedPeriods : [],
  }
}

/**
 * 月度汇总。`excludedPeriods` 列出「有明细但未锁定、因此未计入统计」的期间，
 * 必须展示给用户（否则会以为报表算错了）。
 */
export async function getMonthlyReport(query: MonthlyReportQuery): Promise<ReportResult<MonthlyReportRow>> {
  const raw = await api.get<MonthlyReportResult | MonthlyReportRow[]>('/reports/monthly', { params: query })
  return normalizeReportResult<MonthlyReportRow>(raw)
}

/** 年度汇总：按人 / 按部门，同样返回 `{ rows, excludedPeriods }`。 */
export async function getAnnualReport(query: AnnualReportQuery): Promise<ReportResult<AnnualReportRow>> {
  const raw = await api.get<AnnualReportResult | AnnualReportRow[]>('/reports/annual', { params: query })
  return normalizeReportResult<AnnualReportRow>(raw)
}

/** 导出二进制；失败时后端返回 JSON 统一响应包，由 downloadFile 负责区分。 */
export function exportReport(payload: ExportRequest, fallbackFilename = 'report.xlsx'): Promise<DownloadedFile> {
  return downloadFile({ url: '/reports/export', method: 'POST', data: payload }, fallbackFilename)
}
