/**
 * 系统参数（sys_config）。
 *
 * ⚠️ 契约 v1.1 (docs/api/openapi.yaml) 还没有 configs 端点——后端正在实现，
 * 形状由控制方给定：
 *   GET  /api/v1/configs            -> [{ key, value, valueType, description, editable }]
 *   PUT  /api/v1/configs/{key}      <- { value }
 * 这里先按该形状写本地类型；契约冻结后应改为由 openapi-typescript 生成。
 */
import { api } from './client'

export type ConfigValueType = 'DECIMAL' | 'INT' | 'DATE_LIST' | 'STRING'

export interface SystemConfig {
  key: string
  value: string
  valueType: ConfigValueType
  description: string
  editable: boolean
}

export function listConfigs(): Promise<SystemConfig[]> {
  return api.get<SystemConfig[]>('/configs')
}

export function updateConfig(key: string, value: string): Promise<SystemConfig> {
  return api.put<SystemConfig>(`/configs/${encodeURIComponent(key)}`, { value })
}
