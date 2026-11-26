/**
 * 导出参数构造。
 *
 * 契约要求：缺少对应报表类型的必填参数会返回 400，且 `PERIOD_SHEET` / `PAYSLIP`
 * 的可选字段不应以 `undefined` 形式混进请求体（保持请求干净、便于审计比对）。
 */
import type { ReportExportParams, ReportGroupBy } from '@/api/models'

export function buildMonthlyParams(periodYm: string, companyId?: string, deptId?: string): ReportExportParams {
  return {
    periodYm,
    ...(companyId ? { companyId } : {}),
    ...(deptId ? { deptId } : {}),
  }
}

export function buildAnnualParams(
  year: string,
  groupBy: ReportGroupBy,
  companyId?: string,
  deptId?: string,
): ReportExportParams {
  return {
    year,
    groupBy,
    ...(companyId ? { companyId } : {}),
    ...(deptId ? { deptId } : {}),
  }
}

/** 期间完整工资表：periodYm 必填，deptId 可选。 */
export function buildPeriodSheetParams(periodYm: string, deptId?: string): ReportExportParams {
  return { periodYm, ...(deptId ? { deptId } : {}) }
}

/** 员工工资条：不传 employeeIds 时按数据范围导出可见人员。 */
export function buildPayslipParams(periodYm: string, employeeIds: readonly string[]): ReportExportParams {
  return { periodYm, ...(employeeIds.length > 0 ? { employeeIds: [...employeeIds] } : {}) }
}
