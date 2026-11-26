/**
 * 系统管理的展示辅助。
 *
 * 权限点目录**必须**来自 `GET /permissions`（契约明确要求），这里只做分组与文案映射，
 * 不维护权限点清单本身。
 */
import type { DataScope, PermissionItem } from '@/api/models'

export interface PermissionGroup {
  module: PermissionItem['module']
  label: string
  items: PermissionItem[]
}

const MODULE_LABELS: Record<PermissionItem['module'], string> = {
  PAYROLL: '核算与薪资',
  SYS: '系统管理',
}

const MODULE_ORDER: PermissionItem['module'][] = ['PAYROLL', 'SYS']

/** 按模块分组，保持契约模块顺序与接口返回顺序。 */
export function groupPermissions(items: readonly PermissionItem[]): PermissionGroup[] {
  return MODULE_ORDER.map((module) => ({
    module,
    label: MODULE_LABELS[module] ?? module,
    items: items.filter((item) => item.module === module),
  })).filter((group) => group.items.length > 0)
}

const DATA_SCOPE_LABELS: Record<DataScope, string> = {
  ALL: '集团全部主体',
  COMPANY: '本公司全部部门',
  DEPT_SUB: '本部门及下级',
  DEPT: '本部门',
  SELF: '仅本人',
}

export function dataScopeLabel(scope: DataScope | null | undefined): string {
  if (!scope) return '—'
  return DATA_SCOPE_LABELS[scope] ?? scope
}

/** 授予 ALL 需要额外持有 sys:role:grant:all（防自我提权）。 */
export function requiresGrantAll(scope: DataScope): boolean {
  return scope === 'ALL'
}

/** 与契约一致的密码最小长度。 */
export const PASSWORD_MIN_LENGTH = 8

export function validatePassword(password: string): string | null {
  if (!password) return '请输入密码'
  if (password.length < PASSWORD_MIN_LENGTH) return `密码至少 ${PASSWORD_MIN_LENGTH} 位`
  if (password.length > 128) return '密码不能超过 128 位'
  return null
}
