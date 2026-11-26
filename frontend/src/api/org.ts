import { api } from './client'
import type { Company, Dept } from './models'

export function listCompanies(): Promise<Company[]> {
  return api.get<Company[]>('/companies')
}

export function listDepts(companyId: string): Promise<Dept[]> {
  return api.get<Dept[]>('/depts', { params: { companyId } })
}
