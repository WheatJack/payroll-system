/**
 * 人员展示辅助。
 *
 * `seniorityYears` 在契约里是 required、`socialSeniorityYears` 可空；
 * 但**运行时**必须容忍任何类型：后端曾把工龄返回成数字 `6`（契约要求 `"6.00"`），
 * 一个字段类型漂移不能让整张人员表白屏。统一走 money.asMoneyString 做安全适配。
 */
import type { Employee } from '@/api/models'
import { asMoneyString } from './money'

/** 工龄/社会工龄：字符串去空白、有限数字按两位小数；其余（null/undefined/对象）显示 — */
function pickDecimal(value: unknown): string {
  return asMoneyString(value) ?? '—'
}

/** 本司工龄（按入职日期计算，只读）。 */
export function employeeSeniority(row: Employee): string {
  return pickDecimal(row.seniorityYears)
}

/** 社会工龄（按参加工作时间计算，只读）。 */
export function employeeSocialSeniority(row: Employee): string {
  return pickDecimal(row.socialSeniorityYears)
}

/** 工龄口径说明（人员列表与编制台列头共用） */
export const SENIORITY_TOOLTIP =
  '按入职日期至核算期间末日计算整年数；离职当月按实际工作日折算'

/**
 * 部门下拉要按「员工所属主体」过滤（后端开始拒绝员工部门与主体不一致）。
 * 编辑用员工自己的 companyId；新建用当前登录用户的主体。
 */
export function resolveEmployeeCompanyId(
  employee: Pick<Employee, 'companyId'> | null | undefined,
  fallback: string,
): string {
  return employee?.companyId || fallback
}

/** 部门展示：优先 deptName，缺失才退回 deptId。 */
export function employeeDeptLabel(row: Employee): string {
  return row.deptName || row.deptId || '—'
}
