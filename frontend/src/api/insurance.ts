import { api } from './client'
import type {
  InsuranceParam,
  SocialInsurance,
  SocialInsuranceCreateRequest,
  SocialInsuranceUpdateRequest,
  SpecialDeduction,
  SpecialDeductionCreateRequest,
} from './models'

/* ------------------------------ 参保信息 ------------------------------ */

export function listSocialInsurance(employeeId: string): Promise<SocialInsurance[]> {
  return api.get<SocialInsurance[]>(`/employees/${employeeId}/social-insurance`)
}

/** 同一员工的生效区间不可重叠（后端 409 兜底）。 */
export function createSocialInsurance(
  employeeId: string,
  payload: SocialInsuranceCreateRequest,
): Promise<SocialInsurance> {
  return api.post<SocialInsurance>(`/employees/${employeeId}/social-insurance`, payload)
}

export function updateSocialInsurance(
  id: string,
  payload: SocialInsuranceUpdateRequest,
): Promise<SocialInsurance> {
  return api.put<SocialInsurance>(`/social-insurance/${id}`, payload)
}

/** 某城市的险种个人缴纳参数（比例/基数上下限）。 */
export function listInsuranceParams(cityCode: string): Promise<InsuranceParam[]> {
  return api.get<InsuranceParam[]>('/insurance-params', { params: { cityCode } })
}

/* --------------------------- 专项附加扣除 --------------------------- */

export function listSpecialDeductions(employeeId: string): Promise<SpecialDeduction[]> {
  return api.get<SpecialDeduction[]>(`/employees/${employeeId}/special-deductions`)
}

export function createSpecialDeduction(
  employeeId: string,
  payload: SpecialDeductionCreateRequest,
): Promise<SpecialDeduction> {
  return api.post<SpecialDeduction>(`/employees/${employeeId}/special-deductions`, payload)
}

export function deleteSpecialDeduction(id: string): Promise<null> {
  return api.delete<null>(`/special-deductions/${id}`)
}
