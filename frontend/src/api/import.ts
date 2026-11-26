import { api } from './client'
import { downloadFile, type DownloadedFile } from './download'
import type {
  ImportBizType,
  ImportCommitResult,
  ImportMode,
  ImportValidateResult,
} from './models'

/** 下载模板（列头由工资项字典动态生成）。 */
export function downloadImportTemplate(
  periodId: string,
  bizType: ImportBizType,
  fallbackFilename = 'import-template.xlsx',
): Promise<DownloadedFile> {
  return downloadFile({ url: '/import/template', method: 'GET', params: { periodId, bizType } }, fallbackFilename)
}

export interface ValidateImportPayload {
  file: File
  periodId: string
  bizType: ImportBizType
  importMode: ImportMode
}

/** 上传并预校验（不落核算数据，但持久化批次与错误明细）。 */
export function validateImport(payload: ValidateImportPayload): Promise<ImportValidateResult> {
  const form = new FormData()
  form.append('file', payload.file, payload.file.name)
  form.append('periodId', payload.periodId)
  form.append('bizType', payload.bizType)
  form.append('importMode', payload.importMode)
  // axios 1.x 在 Content-Type 含 application/json 时会把 FormData **序列化成 JSON**
  // （见 axios/lib/defaults transformRequest）。这里显式置 null 删除该头，
  // 让浏览器自己补 multipart boundary。
  return api.post<ImportValidateResult>('/import/validate', form, { headers: { 'Content-Type': null } })
}

/** 刷新/换人后按批次号回捞批次与错误明细。 */
export function getImportBatch(batchId: string): Promise<ImportValidateResult> {
  return api.get<ImportValidateResult>(`/import/${batchId}`)
}

/** 确认提交；failRows > 0 时后端返回 409 且 data.failRows 给出失败行数。 */
export function commitImport(batchId: string): Promise<ImportCommitResult> {
  return api.post<ImportCommitResult>(`/import/${batchId}/commit`)
}

/** 错误报告（xlsx，需 payroll:export）。 */
export function downloadImportErrors(batchId: string, fallbackFilename = 'import-errors.xlsx'): Promise<DownloadedFile> {
  return downloadFile({ url: `/import/${batchId}/errors`, method: 'GET' }, fallbackFilename)
}
