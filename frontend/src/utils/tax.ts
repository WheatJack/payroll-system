/**
 * 个税社保的展示辅助：中文映射、城市、税率百分比、导出 CSV。
 *
 * 金额/税率都是字符串；百分比换算走 money.mul（不做浮点运算）。
 */
import type { AnnualTaxRow, DeductionType } from '@/api/models'
import { mul, round } from './money'

/** 专项附加扣除类型的中文名（deductionType 是枚举，界面必须显示中文）。 */
export const DEDUCTION_TYPE_LABELS: Record<DeductionType, string> = {
  CHILD_EDU: '子女教育',
  INFANT_CARE: '婴幼儿照护',
  CONTINUE_EDU: '继续教育',
  HOUSING_LOAN: '住房贷款利息',
  HOUSING_RENT: '住房租金',
  ELDERLY_CARE: '赡养老人',
}

export function deductionTypeLabel(type: string): string {
  return DEDUCTION_TYPE_LABELS[type as DeductionType] ?? type
}

/** 险种中文名。 */
export const INSURANCE_TYPE_LABELS: Record<string, string> = {
  PENSION: '养老保险',
  MEDICAL: '医疗保险',
  UNEMPLOYMENT: '失业保险',
  INJURY: '工伤保险',
  MATERNITY: '生育保险',
  HOUSING_FUND: '住房公积金',
}

export function insuranceTypeLabel(type: string): string {
  return INSURANCE_TYPE_LABELS[type] ?? type
}

/**
 * 参保城市。契约没有「城市列表」端点（`GET /insurance-params` 要求传 cityCode），
 * 这里给常见的三城作为下拉；后端出现其它城市编码时直接显示编码本身（filterable 可输入）。
 */
export interface InsuranceCity {
  code: string
  name: string
}

export const INSURANCE_CITIES: readonly InsuranceCity[] = [
  { code: 'BEIJING', name: '北京' },
  { code: 'SHANGHAI', name: '上海' },
  { code: 'SHENZHEN', name: '深圳' },
]

export function insuranceCityLabel(code: string | null | undefined): string {
  if (!code) return '—'
  return INSURANCE_CITIES.find((city) => city.code === code)?.name ?? code
}

/** 税率字符串（如 "0.0800"）→ "8.00%"；非法值显示 —。 */
export function formatRateAsPercent(rate: string | null | undefined): string {
  if (!rate) return '—'
  try {
    return `${round(mul(rate, '100'), 2)}%`
  } catch {
    return '—'
  }
}

/** 年度个税导出 CSV 的表头。 */
export const TAX_CSV_HEADERS = [
  '工号',
  '姓名',
  '部门',
  '计薪月数',
  '累计收入',
  '累计减除',
  '累计社保',
  '累计公积金',
  '累计专项',
  '应纳税所得额',
  '累计已预扣',
  '最近一期个税',
  '税负率',
] as const

function csvCell(value: string): string {
  if (/[",\n\r]/.test(value)) {
    return `"${value.replace(/"/g, '""')}"`
  }
  return value
}

/**
 * 年度个税核对导出内容（CSV）。
 * 契约的导出端点没有 ANNUAL_TAX 类型，这里先在前端导出 CSV（带 BOM，Excel 可直接打开）；
 * 后端补导出类型后可替换为 xlsx。
 */
export function buildTaxCsv(rows: readonly AnnualTaxRow[]): string {
  const lines = [TAX_CSV_HEADERS.join(',')]
  for (const row of rows) {
    lines.push(
      [
        row.empNo,
        row.empName,
        row.deptName ?? '',
        String(row.paidMonths),
        row.cumIncome,
        row.cumBasicDeduction,
        row.cumSocial,
        row.cumFund,
        row.cumSpecial,
        row.taxable,
        row.cumTax,
        row.lastTax,
        formatRateAsPercent(row.effectiveRate),
      ]
        .map(csvCell)
        .join(','),
    )
  }
  // BOM 让 Excel 正确识别 UTF-8 中文
  return `﻿${lines.join('\r\n')}`
}
