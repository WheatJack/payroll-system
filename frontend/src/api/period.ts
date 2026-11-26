import { api } from './client'
import type { GenerateResult, Period, PeriodCreateRequest, RunProgress } from './models'

/** 期间列表，按 periodYm 倒序；不传 companyId 取当前用户所属主体。 */
export function listPeriods(companyId?: string): Promise<Period[]> {
  return api.get<Period[]>('/periods', { params: companyId ? { companyId } : {} })
}

/** 按 (companyId, periodYm) 幂等：已存在时直接返回已有期间。 */
export function createPeriod(payload: PeriodCreateRequest): Promise<Period> {
  return api.post<Period>('/periods', payload)
}

/** 异步生成，立即返回 runId，随后轮询 getRun。 */
export function generatePeriod(periodId: string): Promise<GenerateResult> {
  return api.post<GenerateResult>(`/periods/${periodId}/generate`)
}

export function getRun(runId: string): Promise<RunProgress> {
  return api.get<RunProgress>(`/runs/${runId}`)
}

export function lockPeriod(periodId: string): Promise<Period> {
  return api.post<Period>(`/periods/${periodId}/lock`)
}

/** 解锁为高危操作，必须填写原因（写入审计）。 */
export function unlockPeriod(periodId: string, reason: string): Promise<Period> {
  return api.post<Period>(`/periods/${periodId}/unlock`, { reason })
}
