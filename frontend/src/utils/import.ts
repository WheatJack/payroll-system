/**
 * 导入批次的状态判定（与契约 v1.1 对齐）。
 *
 * 关键约束：`failRows > 0` 时**禁止提交**——后端会返回 409 且 `data.failRows` 给出数量，
 * 前端要在按钮上就置灰并说明原因，不能等用户点了才报错。
 */
import type { ImportBatchStatus, ImportMode, ImportValidateResult } from '@/api/models'

/**
 * 导入模式里**可用的**选项。
 * `APPEND`（有则累加）后端已下线，选它必然 400，因此不在界面上提供。
 */
export const AVAILABLE_IMPORT_MODES = ['UPSERT', 'INSERT_ONLY'] as const
export type AvailableImportMode = (typeof AVAILABLE_IMPORT_MODES)[number]

export const IMPORT_MODE_LABELS: Record<AvailableImportMode, string> = {
  UPSERT: '有则覆盖',
  INSERT_ONLY: '有则跳过',
}

/** 防御性判断：历史上可能有人传 APPEND。 */
export function isAvailableImportMode(mode: ImportMode | string): mode is AvailableImportMode {
  return (AVAILABLE_IMPORT_MODES as readonly string[]).includes(mode)
}

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

/* ------------------------------ 异步校验轮询 ------------------------------ */

export const IMPORT_POLL_INTERVAL_MS = 1000
export const IMPORT_POLL_MAX_ATTEMPTS = 60

/** 终态：不再轮询（COMMITTED 也视为终态，避免重复提交后继续打接口）。 */
export function isTerminalBatchStatus(status: ImportBatchStatus | string): boolean {
  return status === 'VALIDATED' || status === 'FAILED' || status === 'COMMITTED'
}

/** 契约：`status=VALIDATING` 时可轮询 `GET /import/{batchId}` 获取进度。 */
export function shouldKeepPolling(status: ImportBatchStatus | string): boolean {
  return status === 'VALIDATING'
}

/** 轮询次数是否已耗尽（超时后给手动刷新入口）。 */
export function pollExhausted(attempts: number, max = IMPORT_POLL_MAX_ATTEMPTS): boolean {
  return attempts >= max
}
