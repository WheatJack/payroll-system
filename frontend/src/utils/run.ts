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
