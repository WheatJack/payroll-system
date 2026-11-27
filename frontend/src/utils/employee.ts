/**
 * 人员展示辅助。
 *
 * `seniorityYears` / `socialSeniorityYears` 是后端正在补的字段（EmployeeDTO），
 * 当前冻结的 v1.1 契约里还没有。这里用**局部扩展类型**读取并做空值兜底，
 * 不改 `api/types.ts`（必须由契约生成）；等契约更新后删掉这层 shim 即可。
 */
import type { Employee } from '@/api/models'

type EmployeeWithSeniority = Employee & {
  seniorityYears?: string | null
  socialSeniorityYears?: string | null
}

function pickDecimal(value: string | null | undefined): string {
  return value && value.trim() !== '' ? value : '—'
}

/** 本司工龄（按入职日期计算，只读）。 */
export function employeeSeniority(row: Employee): string {
  return pickDecimal((row as EmployeeWithSeniority).seniorityYears)
}

/** 社会工龄（按参加工作时间计算，只读）。 */
export function employeeSocialSeniority(row: Employee): string {
  return pickDecimal((row as EmployeeWithSeniority).socialSeniorityYears)
}

/** 部门展示：优先 deptName，缺失才退回 deptId。 */
export function employeeDeptLabel(row: Employee): string {
  return row.deptName || row.deptId || '—'
}
