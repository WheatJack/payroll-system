import { api } from './client'
import type { PayItem, PayItemCategory } from './models'

export interface PayItemQuery {
  category?: PayItemCategory
  /** 1 启用 0 停用 */
  status?: 0 | 1
}

export function listPayItems(query: PayItemQuery = {}): Promise<PayItem[]> {
  return api.get<PayItem[]>('/items', { params: query })
}

export function createPayItem(payload: Record<string, unknown>): Promise<PayItem> {
  return api.post<PayItem>('/items', payload)
}

export function updatePayItem(id: string, payload: Record<string, unknown>): Promise<PayItem> {
  return api.put<PayItem>(`/items/${id}`, payload)
}
