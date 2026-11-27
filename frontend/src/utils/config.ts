/**
 * 系统参数输入校验。
 *
 * 数值类（DECIMAL / INT）一律按**字符串**校验，走 money 工具（coerce），
 * 不使用 Number()/parseFloat；只有日期拆分用整数运算做日历合法性检查。
 */
import type { ConfigValueType } from '@/api/config'
import { coerce } from './money'

const ISO_DATE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/
const INT_PATTERN = /^-?\d+$/

export interface ConfigInputResult {
  ok: boolean
  /** 校验通过后的规范值（提交给后端的就是它） */
  value: string
  /** 校验失败时的人话原因 */
  error: string | null
}

/** 真实日历日期校验（挡掉 2026-02-30 这类）。 */
export function isValidIsoDate(value: string): boolean {
  if (!ISO_DATE.test(value)) return false
  const [year, month, day] = value.split('-').map((part) => Number(part))
  const date = new Date(Date.UTC(year, month - 1, day))
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  )
}

/** 日期列表：支持中英文逗号/分号/空白分隔，去重保序。 */
export function parseDateList(raw: string): string[] {
  const parts = raw
    .split(/[,，;；\s]+/)
    .map((part) => part.trim())
    .filter((part) => part !== '')
  return [...new Set(parts)]
}

export function normalizeConfigValue(valueType: ConfigValueType, raw: string): ConfigInputResult {
  const input = (raw ?? '').trim()

  switch (valueType) {
    case 'DECIMAL': {
      if (input === '') return { ok: false, value: input, error: '请输入数值' }
      const cleaned = coerce(input)
      if (cleaned === null) return { ok: false, value: input, error: '请输入合法数值（可含小数，如 21.75）' }
      return { ok: true, value: cleaned, error: null }
    }
    case 'INT': {
      if (input === '') return { ok: false, value: input, error: '请输入整数' }
      const cleaned = coerce(input)
      if (cleaned === null || !INT_PATTERN.test(cleaned)) {
        return { ok: false, value: input, error: '请输入整数（不含小数）' }
      }
      return { ok: true, value: cleaned, error: null }
    }
    case 'DATE_LIST': {
      const dates = parseDateList(input)
      if (dates.length === 0) return { ok: false, value: input, error: '请至少填写一个日期' }
      const invalid = dates.filter((date) => !isValidIsoDate(date))
      if (invalid.length > 0) {
        return { ok: false, value: input, error: `无效日期：${invalid.join('、')}（格式 YYYY-MM-DD）` }
      }
      return { ok: true, value: dates.join(','), error: null }
    }
    case 'STRING':
    default:
      return { ok: true, value: input, error: null }
  }
}

/** 输入控件的提示文案。 */
export function configInputPlaceholder(valueType: ConfigValueType): string {
  switch (valueType) {
    case 'DECIMAL':
      return '如 21.75'
    case 'INT':
      return '如 30'
    case 'DATE_LIST':
      return '如 2026-01-01,2026-02-17'
    default:
      return '请输入参数值'
  }
}
