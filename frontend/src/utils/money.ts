/**
 * 金额铁律（docs/详细设计.md §4.4）
 *
 * 1. 全链路金额都是**字符串**，任何地方不得出现 `Number()` / `parseFloat` / `+str`。
 * 2. 本模块是唯一允许做金额算术的地方，内部使用 decimal.js（十进制精确运算）。
 * 3. `add` / `sub` 按参与运算的最大小数位（至少 2 位）输出，**不做额外舍入**，
 *    因此「合计 = 明细逐项加总」恒成立；需要展示统一 2 位时显式调用 `round2`。
 * 4. `mul` 输出小数位 = 两个因数小数位之和，同样不舍入。
 */

import Decimal from 'decimal.js'

Decimal.set({
  precision: 40,
  rounding: Decimal.ROUND_HALF_UP,
  toExpNeg: -30,
  toExpPos: 30,
})

export type Money = string

const MONEY_PATTERN = /^-?\d+(?:\.\d+)?$/

/** 严格解析金额字符串；拒绝空串、科学计数法、千分位、数字类型。 */
function toDecimal(value: string, label = 'amount'): Decimal {
  if (typeof value !== 'string') {
    throw new TypeError(`${label} 必须是字符串金额，收到 ${typeof value}`)
  }
  const trimmed = value.trim()
  if (!MONEY_PATTERN.test(trimmed)) {
    throw new Error(`${label} 不是合法金额字符串: ${JSON.stringify(value)}`)
  }
  return new Decimal(trimmed)
}

/** 字符串表示的小数位数（"8600.00" -> 2，"0" -> 0）。 */
function scaleOf(value: string): number {
  const trimmed = value.trim()
  const match = /^-?\d+(?:\.(\d+))?$/.exec(trimmed)
  return match?.[1]?.length ?? 0
}

/** a + b，小数位 = max(scale(a), scale(b), 2)，精确无舍入。 */
export function add(a: string, b: string): string {
  const decimals = Math.max(scaleOf(a), scaleOf(b), 2)
  return toDecimal(a, 'a').plus(toDecimal(b, 'b')).toFixed(decimals)
}

/** a - b，规则同 add。 */
export function sub(a: string, b: string): string {
  const decimals = Math.max(scaleOf(a), scaleOf(b), 2)
  return toDecimal(a, 'a').minus(toDecimal(b, 'b')).toFixed(decimals)
}

/** a × b，小数位 = scale(a) + scale(b)，精确无舍入。 */
export function mul(a: string, b: string): string {
  const decimals = scaleOf(a) + scaleOf(b)
  return toDecimal(a, 'a').times(toDecimal(b, 'b')).toFixed(decimals)
}

/** a ÷ b，必须显式指定结果小数位，避免出现无限小数。 */
export function div(a: string, b: string, scale = 2): string {
  const divisor = toDecimal(b, 'b')
  if (divisor.isZero()) {
    throw new Error('除数不能为 0')
  }
  return toDecimal(a, 'a').dividedBy(divisor).toFixed(scale)
}

/** 四舍五入到指定位数（默认 2 位，HALF_UP）。 */
export function round(value: string, scale = 2): string {
  if (!Number.isInteger(scale) || scale < 0) {
    throw new Error(`非法小数位: ${scale}`)
  }
  return toDecimal(value).toFixed(scale)
}

/** 展示用：统一两位小数。 */
export function round2(value: string): string {
  return round(value, 2)
}

/** 数值相等（"0.30" 与 "0.3" 相等）。 */
export function eq(a: string, b: string): boolean {
  return toDecimal(a, 'a').eq(toDecimal(b, 'b'))
}

/** 是否为 0。 */
export function isZero(value: string): boolean {
  return toDecimal(value).isZero()
}

/** 比较：a < b 返回 -1，相等 0，a > b 返回 1。 */
export function compare(a: string, b: string): -1 | 0 | 1 {
  const result = toDecimal(a, 'a').comparedTo(toDecimal(b, 'b'))
  return result < 0 ? -1 : result > 0 ? 1 : 0
}

/** 取负。 */
export function neg(value: string): string {
  const decimals = Math.max(scaleOf(value), 2)
  return toDecimal(value).negated().toFixed(decimals)
}

/** 明细加总：逐项 add，不做二次舍入（合计 === 明细加总）。 */
export function sum(values: readonly string[], initial = '0'): string {
  return values.reduce((acc, item) => add(acc, item), initial)
}

/** 千分位展示（仅展示层使用，不参与运算）。 */
export function format(value: string): string {
  const fixed = round2(value)
  const negative = fixed.startsWith('-')
  const [intPart, fracPart] = (negative ? fixed.slice(1) : fixed).split('.')
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `${negative ? '-' : ''}${grouped}.${fracPart}`
}

/** 统一的 0 值。 */
export const ZERO: Money = '0.00'

/**
 * 展示兜底：空值/非法金额一律显示 0.00。
 * 单元格渲染用它，避免一个坏字段让整张表抛错白屏（与合计行容错保持一致）。
 */
export function formatOrZero(value: string | null | undefined): string {
  if (value === null || value === undefined || value.trim() === '') return ZERO
  try {
    return format(value)
  } catch {
    return ZERO
  }
}

/**
 * 清洗用户输入或 Excel 粘贴的金额文本（剔除货币符号、千分位、空白），
 * 合法则返回金额字符串，非法返回 null。仅做字符串清洗，不做数值转换。
 */
export function coerce(value: string): string | null {
  if (typeof value !== 'string') return null
  const cleaned = value.replace(/[,\s￥¥$]/g, '').replace(/^\+/, '')
  if (cleaned === '') return null
  return MONEY_PATTERN.test(cleaned) ? cleaned : null
}
