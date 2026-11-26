import { add, coerce, compare, div, eq, format, formatOrZero, isZero, mul, neg, round, round2, sub, sum } from '../money'
import { parseClipboardMatrix } from '../paste'

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

describe('money 的精度边界：乘除、负数、HALF_UP 与 scale ≠ 2', () => {
  test('乘法精确：0.1 × 0.2 不出现浮点尾数', () => {
    expect(mul('0.1', '0.2')).toBe('0.02')
    expect(mul('1.5', '2.25')).toBe('3.375')
    expect(mul('100.00', '1.5')).toBe('150.000')
  })

  test('乘法保留负号且不丢精度', () => {
    expect(mul('-2.5', '4')).toBe('-10.0')
  })

  test('除法必须显式给精度，除零抛错', () => {
    expect(div('10', '3', 2)).toBe('3.33')
    expect(div('10', '4', 2)).toBe('2.50')
    expect(div('1', '8', 4)).toBe('0.1250')
    expect(() => div('1', '0')).toThrow('除数不能为 0')
  })

  test('负数加减', () => {
    expect(add('-100.00', '50.00')).toBe('-50.00')
    expect(sub('0.00', '0.01')).toBe('-0.01')
    expect(neg('-5.00')).toBe('5.00')
    expect(neg('0.00')).toBe('0.00')
  })

  test('0.005 按 HALF_UP 进位（正负都远离 0）', () => {
    expect(round('0.005', 2)).toBe('0.01')
    expect(round('-0.005', 2)).toBe('-0.01')
    expect(round('2.5', 0)).toBe('3')
    expect(round('1.2345', 3)).toBe('1.235')
    expect(round('1.2345', 4)).toBe('1.2345')
  })

  test('scale ≠ 2 的加减按最大小数位输出，不做额外舍入', () => {
    expect(add('1.234', '2.345')).toBe('3.579')
    expect(sub('1.2', '0.001')).toBe('1.199')
    expect(sum(['1.234', '2.345', '0.001'])).toBe('3.580')
  })

  test('isZero / compare / format', () => {
    expect(isZero('0.00')).toBe(true)
    expect(isZero('0.01')).toBe(false)
    expect(compare('1.00', '1.00')).toBe(0)
    expect(compare('2', '1.99')).toBe(1)
    expect(compare('-2', '-1')).toBe(-1)
    expect(compare('-1', '-2')).toBe(1)
    expect(format('1234567.5')).toBe('1,234,567.50')
    expect(format('-0.5')).toBe('-0.50')
  })

  test('eq 兼容不同写法与正负零', () => {
    expect(eq('-0.00', '0')).toBe(true)
    expect(eq('1.2340', '1.234')).toBe(true)
    expect(eq('1.234', '1.235')).toBe(false)
  })

  test('formatOrZero 对空值/非法金额兜底为 0.00，不抛错', () => {
    expect(formatOrZero('')).toBe('0.00')
    expect(formatOrZero(null)).toBe('0.00')
    expect(formatOrZero(undefined)).toBe('0.00')
    expect(formatOrZero('abc')).toBe('0.00')
    expect(formatOrZero('1234.5')).toBe('1,234.50')
    expect(formatOrZero('-0.5')).toBe('-0.50')
  })
})

describe('money.coerce 仅做字符串清洗', () => {
  test('剔除货币符号、千分位与空白', () => {
    expect(coerce(' ￥1,234.56 ')).toBe('1234.56')
    expect(coerce('+8000')).toBe('8000')
  })

  test('非法输入返回 null，不做数字兜底', () => {
    expect(coerce('abc')).toBeNull()
    expect(coerce('1.2.3')).toBeNull()
    expect(coerce('')).toBeNull()
    expect(coerce('1e5')).toBeNull()
  })
})

describe('parseClipboardMatrix 解析 Excel 多行粘贴', () => {
  test('按制表符分列、按换行分行，去掉末尾空行', () => {
    expect(parseClipboardMatrix('100.00\t200.00\r\n300.00\t400.00\r\n')).toEqual([
      ['100.00', '200.00'],
      ['300.00', '400.00'],
    ])
  })

  test('单元格保留空值，不用 0 填充', () => {
    expect(parseClipboardMatrix('1\t\t3')).toEqual([['1', '', '3']])
  })
})
