/**
 * 导入批次的状态判定（与契约 v1.1 对齐）。
 *
 * 关键约束：`failRows > 0` 时**禁止提交**——后端会返回 409 且 `data.failRows` 给出数量，
 * 前端要在按钮上就置灰并说明原因，不能等用户点了才报错。
 */
import type { ImportBatchStatus, ImportValidateResult } from '@/api/models'

export type ImportBatchView = Pick<
  ImportValidateResult,
  'batchId' | 'status' | 'totalRows' | 'successRows' | 'failRows' | 'errors'
>

/** 是否允许点击「确认提交」。 */
export function canCommit(batch: ImportBatchView | null, hasPermission: boolean): boolean {
  return commitBlockedReason(batch, hasPermission) === null
}

/** 不能提交时的原因（可展示文案）；可以提交时返回 null。 */
export function commitBlockedReason(batch: ImportBatchView | null, hasPermission: boolean): string | null {
  if (!hasPermission) return '缺少 payroll:import:submit 权限'
  if (!batch) return '请先上传文件并完成预校验'
  if (batch.status === 'VALIDATING') return '预校验进行中，请稍候'
  if (batch.status === 'FAILED') return '预校验失败，请重新上传'
  if (batch.status === 'COMMITTED') return '该批次已提交，不能重复提交'
  if (batch.failRows > 0) {
    return `预校验有 ${batch.failRows} 行失败（成功 ${batch.successRows} 行），修正后重新上传才能提交`
  }
  if (batch.status !== 'VALIDATED') return '请先完成预校验'
  return null
}

/** 批次摘要，用于状态栏文案。 */
export function batchSummary(batch: ImportBatchView): string {
  return `共 ${batch.totalRows} 行：成功 ${batch.successRows} 行，失败 ${batch.failRows} 行`
}

/** 预校验结果是否「可以进入提交步骤」。 */
export function isReadyToCommit(batch: ImportBatchView | null): boolean {
  return !!batch && batch.status === 'VALIDATED' && batch.failRows === 0
}
