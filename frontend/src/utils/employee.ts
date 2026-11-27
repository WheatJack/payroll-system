/**
 * 人员展示辅助。
 *
 * `seniorityYears` 在契约里是 required、`socialSeniorityYears` 可空；
 * 但后端 `GET /employees` 目前还没返回这两个字段，真实联调时会短暂拿到 undefined，
 * 因此这里保留**运行时**兜底（缺失/空串/null 一律显示 `—`），不做类型层面的假设。
 */
import type { Employee } from '@/api/models'

function pickDecimal(value: string | null | undefined): string {
  return value && value.trim() !== '' ? value : '—'
}

/** 本司工龄（按入职日期计算，只读）。 */
export function employeeSeniority(row: Employee): string {
  return pickDecimal(row.seniorityYears)
}

/** 社会工龄（按参加工作时间计算，只读）。 */
export function employeeSocialSeniority(row: Employee): string {
  return pickDecimal(row.socialSeniorityYears)
}

/** 部门展示：优先 deptName，缺失才退回 deptId。 */
export function employeeDeptLabel(row: Employee): string {
  return row.deptName || row.deptId || '—'
}
