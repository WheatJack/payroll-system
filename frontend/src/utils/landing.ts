/**
 * 登录后的落地页选择。
 *
 * 背景（三权分立）：系统管理员只有 `sys:*`，薪酬专员只有 `payroll:*`。
 * 写死 `/periods` 会让系统管理员登录后直接 403；因此按**路由实际需要的权限**
 * 挑选第一个可访问页面，一份清单同时供登录跳转与根路径 `/` 重定向使用。
 */
export interface LandingRoute {
  path: string
  perm: string
}

/**
 * 顺序即优先级：
 * - 薪酬侧优先「工资期间」（岗位主入口）
 * - 系统侧优先「用户管理」
 */
export const LANDING_ROUTES: readonly LandingRoute[] = [
  { path: '/periods', perm: 'payroll:record:view' },
  { path: '/employees', perm: 'payroll:record:view' },
  { path: '/reports/monthly', perm: 'payroll:record:view' },
  { path: '/reports/annual', perm: 'payroll:record:view' },
  { path: '/system/users', perm: 'sys:user:view' },
  { path: '/system/roles', perm: 'sys:role:view' },
  { path: '/system/depts', perm: 'sys:dept:edit' },
  { path: '/system/companies', perm: 'sys:company:edit' },
]

/**
 * 返回该权限集合可访问的第一个页面路径；一个都进不去时返回 `null`
 * （调用方应跳到「无可用页面」兜底页，而不是 403 死循环）。
 */
export function resolveLandingPath(permissions: readonly string[]): string | null {
  const granted = new Set(permissions)
  const found = LANDING_ROUTES.find((route) => granted.has(route.perm))
  return found ? found.path : null
}
