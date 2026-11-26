import { api } from './client'
import type { CompProfile, CompProfileCreateRequest, CompProfileUpdateRequest } from './models'

/** 按 effectiveFrom 升序返回该员工全部薪酬档案区间。 */
export function listCompProfiles(employeeId: string): Promise<CompProfile[]> {
  return api.get<CompProfile[]>(`/employees/${employeeId}/comp-profiles`)
}

export function createCompProfile(payload: CompProfileCreateRequest): Promise<CompProfile> {
  return api.post<CompProfile>('/comp-profiles', payload)
}

/**
 * 修改薪酬档案：后端自动截断旧区间并新建一段，返回新的当前区间（id 可能变化）。
 * 历史工资表不会被改写。
 */
export function updateCompProfile(id: string, payload: CompProfileUpdateRequest): Promise<CompProfile> {
  return api.put<CompProfile>(`/comp-profiles/${id}`, payload)
}
