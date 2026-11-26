import type { Dept } from '@/api/models'

export interface DeptNode {
  value: string
  label: string
  children: DeptNode[]
}

/** 契约返回扁平部门（parentId/ancestors），这里按 parentId 组装成树。 */
export function buildDeptTree(depts: Dept[]): DeptNode[] {
  const nodes = new Map<string, DeptNode>()
  for (const dept of depts) {
    nodes.set(dept.id, { value: dept.id, label: dept.deptName, children: [] })
  }
  const roots: DeptNode[] = []
  for (const dept of depts) {
    const node = nodes.get(dept.id)!
    const parent = nodes.get(dept.parentId)
    if (parent) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }
  return roots
}

const EMPLOYEE_TYPE_LABELS: Record<string, string> = {
  REGULAR: '正式',
  PROBATION: '试用',
  INTERN: '实习',
  LABOR: '劳务',
  OUTSOURCE: '外包',
  REHIRE: '返聘',
}

export function employeeTypeLabel(type: string | null | undefined): string {
  if (!type) return '—'
  return EMPLOYEE_TYPE_LABELS[type] ?? type
}

const EMPLOYEE_STATUS_LABELS: Record<number, string> = {
  1: '在职',
  2: '离职',
  3: '停薪留职',
}

export function employeeStatusLabel(status: number): string {
  return EMPLOYEE_STATUS_LABELS[status] ?? String(status)
}

const GENDER_LABELS: Record<number, string> = {
  0: '未知',
  1: '男',
  2: '女',
}

export function genderLabel(gender: number | null | undefined): string {
  if (gender === null || gender === undefined) return '—'
  return GENDER_LABELS[gender] ?? String(gender)
}
