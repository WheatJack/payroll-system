import { api } from './client'
import type {
  Employee,
  EmployeeCreateRequest,
  EmployeePage,
  EmployeeSensitive,
  EmployeeType,
  EmployeeUpdateRequest,
} from './models'

export interface EmployeeQuery {
  current: number
  size: number
  /** 姓名或工号模糊匹配 */
  keyword?: string
  deptId?: string
  employeeType?: EmployeeType
  /** 1 在职 2 离职 3 停薪留职 */
  status?: 1 | 2 | 3
}

/** 人员分页查询；证件号只返回掩码，前端不做反查。 */
export function listEmployees(query: EmployeeQuery): Promise<EmployeePage> {
  return api.get<EmployeePage>('/employees', { params: query })
}

export function getEmployee(id: string): Promise<Employee> {
  return api.get<Employee>(`/employees/${id}`)
}

export function createEmployee(payload: EmployeeCreateRequest): Promise<Employee> {
  return api.post<Employee>('/employees', payload)
}

/** 只提交需要修改的字段；idNo 不修改时不要带上。 */
export function updateEmployee(id: string, payload: EmployeeUpdateRequest): Promise<Employee> {
  return api.put<Employee>(`/employees/${id}`, payload)
}

/**
 * 证件号/手机号明文（高危，需要 payroll:sensitive:view）。
 * 每次调用都会写审计日志；调用前必须二次确认，明文只放在组件局部状态里。
 */
export function getEmployeeSensitive(id: string): Promise<EmployeeSensitive> {
  return api.get<EmployeeSensitive>(`/employees/${id}/sensitive`)
}
