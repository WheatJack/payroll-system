import { describe, expect, test } from 'vitest'
import { hasGenerateWarnings, normalizeWarnings } from '../run'

describe('生成批次警告', () => {
  test('逐条保留非空警告（含不足月折算/节假日未配置）', () => {
    expect(
      normalizeWarnings([
        '本期有 2 人按不足月折算，但法定节假日未配置，折算天数可能偏多',
        '   ',
        '',
        null,
      ]),
    ).toEqual(['本期有 2 人按不足月折算，但法定节假日未配置，折算天数可能偏多'])
  })

  test('空/缺失时返回空数组', () => {
    expect(normalizeWarnings(null)).toEqual([])
    expect(normalizeWarnings(undefined)).toEqual([])
    expect(normalizeWarnings([])).toEqual([])
  })

  test('hasGenerateWarnings 判定', () => {
    expect(hasGenerateWarnings(['warning'])).toBe(true)
    expect(hasGenerateWarnings([' '])).toBe(false)
    expect(hasGenerateWarnings(undefined)).toBe(false)
  })
})
