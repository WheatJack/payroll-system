import { describe, expect, test } from 'vitest'
import type { AnnualTaxRow } from '@/api/models'
import {
  TAX_CSV_HEADERS,
  buildTaxCsv,
  deductionTypeLabel,
  formatRateAsPercent,
  insuranceCityLabel,
  insuranceTypeLabel,
} from '../tax'

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
    expect(insuranceCityLabel('BEIJING')).toBe('北京')
    expect(insuranceCityLabel('SHENZHEN')).toBe('深圳')
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

describe('年度个税导出 CSV', () => {
  const row: AnnualTaxRow = {
    empNo: 'E001',
    empName: '张三',
    deptName: '研发,一部',
    paidMonths: 12,
    cumIncome: '120000.00',
    cumBasicDeduction: '60000.00',
    cumSocial: '9600.00',
    cumFund: '9600.00',
    cumSpecial: '24000.00',
    taxable: '16800.00',
    cumTax: '504.00',
    lastTax: '42.00',
    effectiveRate: '0.0042',
  }

  test('表头与列顺序固定', () => {
    const content = buildTaxCsv([])
    expect(content.replace('﻿', '')).toBe(TAX_CSV_HEADERS.join(','))
  })

  test('金额原样输出，含逗号/引号的字段正确转义', () => {
    const content = buildTaxCsv([row])
    const lines = content.replace('﻿', '').split('\r\n')
    expect(lines).toHaveLength(2)
    expect(lines[1]).toContain('"研发,一部"')
    expect(lines[1]).toContain('120000.00')
    expect(lines[1]).toContain('0.42%') // 0.0042 -> 0.42%
  })

  test('带 BOM，Excel 打开中文不乱码', () => {
    expect(buildTaxCsv([row]).startsWith('﻿')).toBe(true)
  })

  test('引号字段双写转义', () => {
    const content = buildTaxCsv([{ ...row, empName: '张"三' }])
    expect(content).toContain('"张""三"')
  })
})
