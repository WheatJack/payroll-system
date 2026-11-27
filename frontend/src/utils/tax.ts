/**
 * 个税社保的展示辅助：中文映射、城市、税率百分比、导出 CSV。
 *
 * 金额/税率都是字符串；百分比换算走 money.mul（不做浮点运算）。
 */
import type { DeductionType } from '@/api/models'
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
  { code: 'BJ', name: '北京' },
  { code: 'SH', name: '上海' },
  { code: 'SZ', name: '深圳' },
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
