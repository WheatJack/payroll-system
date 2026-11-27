import { describe, expect, test } from 'vitest'
import { deductionTypeLabel, formatRateAsPercent, insuranceCityLabel, insuranceTypeLabel } from '../tax'

describe('专项附加扣除中文映射', () => {
  test('六个枚举值都有中文名', () => {
    expect(deductionTypeLabel('CHILD_EDU')).toBe('子女教育')
    expect(deductionTypeLabel('INFANT_CARE')).toBe('婴幼儿照护')
    expect(deductionTypeLabel('CONTINUE_EDU')).toBe('继续教育')
    expect(deductionTypeLabel('HOUSING_LOAN')).toBe('住房贷款利息')
    expect(deductionTypeLabel('HOUSING_RENT')).toBe('住房租金')
    expect(deductionTypeLabel('ELDERLY_CARE')).toBe('赡养老人')
  })

  test('未知枚举退回原值而不是 undefined', () => {
    expect(deductionTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })

  test('险种中文名', () => {
    expect(insuranceTypeLabel('PENSION')).toBe('养老保险')
    expect(insuranceTypeLabel('HOUSING_FUND')).toBe('住房公积金')
    expect(insuranceTypeLabel('UNKNOWN')).toBe('UNKNOWN')
  })

  test('城市编码显示中文，未知编码原样显示', () => {
    expect(insuranceCityLabel('BJ')).toBe('北京')
    expect(insuranceCityLabel('SZ')).toBe('深圳')
    expect(insuranceCityLabel('HANGZHOU')).toBe('HANGZHOU')
    expect(insuranceCityLabel(null)).toBe('—')
  })
})

describe('税率格式化（字符串 + money）', () => {
  test('小数转百分比保留两位', () => {
    expect(formatRateAsPercent('0.08')).toBe('8.00%')
    expect(formatRateAsPercent('0.1000')).toBe('10.00%')
    expect(formatRateAsPercent('0.005')).toBe('0.50%')
  })

  test('空/非法显示 —', () => {
    expect(formatRateAsPercent('')).toBe('—')
    expect(formatRateAsPercent(null)).toBe('—')
    expect(formatRateAsPercent('abc')).toBe('—')
  })
})
