/**
 * 契约类型的可读别名。
 *
 * 全部派生自 openapi-typescript 生成的 `./types`（冻结的 openapi.yaml），
 * 这里只做重命名，**不新增/修改任何字段**，保证类型与契约同步。
 */
import type { components } from './types'

type S = components['schemas']

export type Id = S['Id']
export type Money = S['Money']
export type DecimalString = S['DecimalString']
export type ErrorCode = S['ErrorCode']
export type PeriodStatus = S['PeriodStatus']
export type EmployeeType = S['EmployeeType']
export type PayItemCategory = S['PayItemCategory']
export type PayDirection = S['PayDirection']
export type PayItemDataType = S['PayItemDataType']
export type DataScope = S['DataScope']
export type RunStatus = S['RunStatus']

export type LoginRequest = S['LoginRequest']
export type CurrentUser = S['CurrentUser']
export type LoginResponse = S['LoginResponse']

export type Company = S['Company']
export type Dept = S['Dept']

export type Employee = S['Employee']
export type EmployeeCreateRequest = S['EmployeeCreateRequest']
export type EmployeeUpdateRequest = S['EmployeeUpdateRequest']
export type EmployeePage = S['EmployeePage']

export type CompProfile = S['CompProfile']
export type CompProfileItem = S['CompProfileItem']
export type CompProfileCreateRequest = S['CompProfileCreateRequest']
export type CompProfileUpdateRequest = S['CompProfileUpdateRequest']

export type PayItem = S['PayItem']
export type PayItemCreateRequest = S['PayItemCreateRequest']
export type PayItemUpdateRequest = S['PayItemUpdateRequest']

export type Period = S['Period']
export type PeriodCreateRequest = S['PeriodCreateRequest']
export type GenerateResult = S['GenerateResult']
export type RunProgress = S['RunProgress']
export type RunError = S['RunError']
export type UnlockRequest = S['UnlockRequest']

export type GridPeriod = S['GridPeriod']
export type GridColumn = S['GridColumn']
export type GridRow = S['GridRow']
export type GridResponse = S['GridResponse']

export type ChangesetItem = S['ChangesetItem']
export type ChangesetChange = S['ChangesetChange']
export type ChangesetRequest = S['ChangesetRequest']
export type ChangesetResult = S['ChangesetResult']
export type ConflictItem = S['ConflictItem']
export type ChangesetConflicts = S['ChangesetConflicts']

// ---------------------------------------------------------------
// v1.1：报表 / 导入 / 系统管理
// ---------------------------------------------------------------
export type PermissionCode = S['PermissionCode']
export type ReportType = S['ReportType']
export type ReportFormat = S['ReportFormat']
export type ReportGroupBy = S['ReportGroupBy']
export type ImportBizType = S['ImportBizType']
export type ImportMode = S['ImportMode']
export type ImportBatchStatus = S['ImportBatchStatus']

export type MonthlyReportRow = S['MonthlyReportRow']
export type AnnualReportRow = S['AnnualReportRow']
export type ReportExportParams = S['ReportExportParams']
export type ExportRequest = S['ExportRequest']

export type ImportError = S['ImportError']
export type ImportValidateResult = S['ImportValidateResult']
export type ImportCommitResult = S['ImportCommitResult']

export type PermissionItem = S['PermissionItem']
export type User = S['User']
export type UserPage = S['UserPage']
export type UserCreateRequest = S['UserCreateRequest']
export type UserUpdateRequest = S['UserUpdateRequest']
export type ResetPasswordRequest = S['ResetPasswordRequest']
export type Role = S['Role']
export type RoleCreateRequest = S['RoleCreateRequest']
export type RoleUpdateRequest = S['RoleUpdateRequest']
export type RoleGrantRequest = S['RoleGrantRequest']
export type CompanyCreateRequest = S['CompanyCreateRequest']
export type CompanyUpdateRequest = S['CompanyUpdateRequest']
export type DeptCreateRequest = S['DeptCreateRequest']
export type DeptUpdateRequest = S['DeptUpdateRequest']

// v1.2：报表结果对象 / 系统参数
export type MonthlyReportResult = S['MonthlyReportResult']
export type AnnualReportResult = S['AnnualReportResult']
export type ConfigItem = S['ConfigItem']
export type ConfigValueType = S['ConfigValueType']
