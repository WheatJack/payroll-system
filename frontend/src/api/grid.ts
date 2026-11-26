import { api } from './client'
import type { ChangesetRequest, ChangesetResult, GridResponse } from './models'

/** 返回该期间全部核算行（不分页），列由 columns 动态驱动。 */
export function getGrid(periodId: string): Promise<GridResponse> {
  return api.get<GridResponse>(`/periods/${periodId}/grid`)
}

/** changeset 批量保存；冲突时抛 409，`error.data.conflicts[].recordId` 列出冲突行。 */
export function saveChangeset(periodId: string, payload: ChangesetRequest): Promise<ChangesetResult> {
  return api.patch<ChangesetResult>(`/periods/${periodId}/records`, payload)
}
