import { describe, expect, test } from 'vitest'
import type { Employee } from '@/api/models'
import {
  employeeDeptLabel,
  employeeSeniority,
  employeeSocialSeniority,
  resolveEmployeeCompanyId,
} from '../employee'

function employee(overrides: Record<string, unknown> = {}): Employee {
  return {
    id: '1',
    companyId: '1',
    empNo: 'E001',
    name: '张三',
    gender: 1,
    idType: 'ID_CARD',
    idNoMask: '****1234',
    mobileMask: null,
    employeeType: 'REGULAR',
    jobLevel: 'P6',
    positionName: null,
    deptId: '1',
    deptName: '北京研发部',
    hireDate: '2020-03-01',
    workStartDate: null,
    leaveDate: null,
    status: 1,
    remark: null,
    ...overrides,
  } as Employee
}

describe('人员工龄与部门展示', () => {
  test('工龄字段存在时原样展示（只读，不做计算）', () => {
    expect(employeeSeniority(employee({ seniorityYears: '6.00' }))).toBe('6.00')
    expect(employeeSocialSeniority(employee({ socialSeniorityYears: '7.50' }))).toBe('7.50')
  })

  test('后端暂未返回该字段（运行时 undefined/null/空串）时显示 — 而不是 undefined/NaN', () => {
    expect(employeeSeniority(employee())).toBe('—')
    expect(employeeSocialSeniority(employee())).toBe('—')
    expect(employeeSeniority(employee({ seniorityYears: '' }))).toBe('—')
    expect(employeeSeniority(employee({ seniorityYears: null }))).toBe('—')
  })

  test('后端把工龄返回成数字时按两位小数展示（6 -> 6.00）', () => {
    expect(employeeSeniority(employee({ seniorityYears: 6 }))).toBe('6.00')
    expect(employeeSocialSeniority(employee({ socialSeniorityYears: 7.5 }))).toBe('7.50')
    expect(employeeSeniority(employee({ seniorityYears: {} }))).toBe('—')
    expect(employeeSeniority(employee({ seniorityYears: Number.NaN }))).toBe('—')
  })

  test('部门下拉按员工所属主体过滤：编辑用员工 companyId，新建用当前用户主体', () => {
    expect(resolveEmployeeCompanyId(employee({ companyId: '2' }), '1')).toBe('2')
    expect(resolveEmployeeCompanyId(null, '1')).toBe('1')
    expect(resolveEmployeeCompanyId(employee({ companyId: '' }), '1')).toBe('1')
  })

  test('部门优先 deptName，缺失才退回 deptId', () => {
    expect(employeeDeptLabel(employee())).toBe('北京研发部')
    expect(employeeDeptLabel(employee({ deptName: '' }))).toBe('1')
    expect(employeeDeptLabel(employee({ deptName: '', deptId: '' }))).toBe('—')
  })
})
