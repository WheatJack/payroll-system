import type { RunProgress } from '@/api/models'

/**
 * 生成进度百分比：跳过的人不会出现在 success/failed 里，旧算法永远到不了 100%。
 * 完成（SUCCESS）直接 100%；进行中把 skipped 计入已完成数。
 */
export function runProgressPercent(
  progress: Pick<RunProgress, 'status' | 'total' | 'success' | 'failed'> & { skipped?: Record<string, string> } | null | undefined,
): number {
  if (!progress || !progress.total || progress.total <= 0) return 0
  if (progress.status === 'SUCCESS') return 100
  const skipped = Object.keys(progress.skipped ?? {}).length
  const done = progress.success + progress.failed + skipped
  return Math.max(0, Math.min(100, Math.round((done / progress.total) * 100)))
}

/**
 * 生成批次的业务警告（v1.2 `RunProgress.warnings`）。
 *
 * 关键一条是「不足月折算但法定节假日未配置 → 折算天数可能偏多」，
 * 直接关系到会不会多发钱，必须逐条展示成黄色警示，不能吞掉。
 */
export function normalizeWarnings(
  warnings: readonly (string | null | undefined)[] | null | undefined,
): string[] {
  return (warnings ?? [])
    .map((warning) => (typeof warning === 'string' ? warning.trim() : ''))
    .filter((warning) => warning !== '')
}

export function hasGenerateWarnings(
  warnings: readonly (string | null | undefined)[] | null | undefined,
): boolean {
  return normalizeWarnings(warnings).length > 0
}
