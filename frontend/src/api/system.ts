import { api } from './client'
import type {
  Company,
  CompanyCreateRequest,
  CompanyUpdateRequest,
  Dept,
  DeptCreateRequest,
  DeptUpdateRequest,
  PermissionItem,
  ResetPasswordRequest,
  Role,
  RoleCreateRequest,
  RoleGrantRequest,
  RoleUpdateRequest,
  User,
  UserCreateRequest,
  UserPage,
  UserUpdateRequest,
} from './models'

/* ------------------------------ 用户 ------------------------------ */

export interface UserQuery {
  current: number
  size: number
  keyword?: string
  companyId?: string
  deptId?: string
  status?: 0 | 1
}

export function listUsers(query: UserQuery): Promise<UserPage> {
  return api.get<UserPage>('/users', { params: query })
}

export function createUser(payload: UserCreateRequest): Promise<User> {
  return api.post<User>('/users', payload)
}

/** 只提交需要修改的字段；username / companyId 不可修改。 */
export function updateUser(id: string, payload: UserUpdateRequest): Promise<User> {
  return api.put<User>(`/users/${id}`, payload)
}

export function deleteUser(id: string): Promise<null> {
  return api.delete<null>(`/users/${id}`)
}

export function resetUserPassword(id: string, payload: ResetPasswordRequest): Promise<User> {
  return api.post<User>(`/users/${id}/reset-password`, payload)
}

/* ------------------------------ 角色与权限 ------------------------------ */

export function listPermissions(): Promise<PermissionItem[]> {
  return api.get<PermissionItem[]>('/permissions')
}

export function listRoles(query: { companyId?: string; status?: 0 | 1 } = {}): Promise<Role[]> {
  return api.get<Role[]>('/roles', { params: query })
}

export function createRole(payload: RoleCreateRequest): Promise<Role> {
  return api.post<Role>('/roles', payload)
}

export function updateRole(id: string, payload: RoleUpdateRequest): Promise<Role> {
  return api.put<Role>(`/roles/${id}`, payload)
}

export function deleteRole(id: string): Promise<null> {
  return api.delete<null>(`/roles/${id}`)
}

/** 全量替换该角色的权限点（传空数组即清空）。 */
export function replaceRoleGrants(id: string, permissions: string[]): Promise<Role> {
  const payload: RoleGrantRequest = { permissions: permissions as RoleGrantRequest['permissions'] }
  return api.put<Role>(`/roles/${id}/grants`, payload)
}

/* ------------------------------ 部门与主体 ------------------------------ */

export function createDept(payload: DeptCreateRequest): Promise<Dept> {
  return api.post<Dept>('/depts', payload)
}

export function updateDept(id: string, payload: DeptUpdateRequest): Promise<Dept> {
  return api.put<Dept>(`/depts/${id}`, payload)
}

/** 有下级部门或关联员工/用户时后端返回 409。 */
export function deleteDept(id: string): Promise<null> {
  return api.delete<null>(`/depts/${id}`)
}

export function createCompany(payload: CompanyCreateRequest): Promise<Company> {
  return api.post<Company>('/companies', payload)
}

export function updateCompany(id: string, payload: CompanyUpdateRequest): Promise<Company> {
  return api.put<Company>(`/companies/${id}`, payload)
}
