import { add, coerce, eq, round2, sub } from '../money'
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
