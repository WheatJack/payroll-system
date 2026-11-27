/**
 * 系统参数（sys_config）。
 *
 * 类型来自冻结契约（openapi.yaml -> api/types.ts 的 ConfigItem / ConfigValueType）。
 */
import { api } from './client'
import type { ConfigItem, ConfigValueType } from './models'

export type SystemConfig = ConfigItem
export type { ConfigValueType }

export function listConfigs(): Promise<SystemConfig[]> {
  return api.get<SystemConfig[]>('/configs')
}

export function updateConfig(key: string, value: string): Promise<SystemConfig> {
  return api.put<SystemConfig>(`/configs/${encodeURIComponent(key)}`, { value })
}
