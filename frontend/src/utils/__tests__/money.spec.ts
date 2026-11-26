import { describe, expect, test } from 'vitest'
import { add, sub, eq, round2 } from '../money'

describe('money 金额运算（字符串进字符串出）', () => {
  test('两位小数相加不产生浮点误差', () => {
    expect(add('0.10', '0.20')).toBe('0.30')
  })

  test('大数相加不丢精度', () => {
    expect(add('123456789012345.67', '0.01')).toBe('123456789012345.68')
  })

  test('合计等于明细加总', () => {
    const items = ['5517.24', '689.66', '600.00']
    const sum = items.reduce((a, b) => add(a, b), '0')
    expect(round2(sum)).toBe('6806.90')
  })

  test('浮点陷阱用例', () => {
    expect(eq(add('0.1', '0.2'), '0.3')).toBe(true)
    expect(sub('8600.00', '300.00')).toBe('8300.00')
  })
})
