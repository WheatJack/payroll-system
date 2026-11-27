import { describe, expect, test } from 'vitest'
import {
  configInputPlaceholder,
  isValidIsoDate,
  normalizeConfigValue,
  parseDateList,
} from '../config'

describe('系统参数：数值校验走 money 工具（不用 Number）', () => {
  test('DECIMAL 接受小数/千分位并清洗', () => {
    expect(normalizeConfigValue('DECIMAL', '21.75')).toEqual({ ok: true, value: '21.75', error: null })
    expect(normalizeConfigValue('DECIMAL', '￥1,234.50').value).toBe('1234.50')
    expect(normalizeConfigValue('DECIMAL', ' 0.0825 ').value).toBe('0.0825')
  })

  test('DECIMAL 非法输入给出人话错误', () => {
    expect(normalizeConfigValue('DECIMAL', '').error).toBe('请输入数值')
    expect(normalizeConfigValue('DECIMAL', 'abc').error).toContain('合法数值')
    expect(normalizeConfigValue('DECIMAL', '1e5').ok).toBe(false)
  })

  test('INT 只接受整数', () => {
    expect(normalizeConfigValue('INT', '30').value).toBe('30')
    expect(normalizeConfigValue('INT', '1,000').value).toBe('1000')
    expect(normalizeConfigValue('INT', '30.5').error).toContain('整数')
    expect(normalizeConfigValue('INT', '-5').value).toBe('-5')
  })
})

describe('系统参数：日期列表', () => {
  test('解析支持中英文逗号/分号/空白，并去重保序', () => {
    expect(parseDateList('2026-01-01,2026-02-17')).toEqual(['2026-01-01', '2026-02-17'])
    expect(parseDateList('2026-01-01，2026-02-17; 2026-01-01')).toEqual(['2026-01-01', '2026-02-17'])
    expect(parseDateList('   ')).toEqual([])
  })

  test('提交前逐项校验 ISO 日期与真实日历', () => {
    const ok = normalizeConfigValue('DATE_LIST', '2026-01-01，2026-02-17')
    expect(ok).toEqual({ ok: true, value: '2026-01-01,2026-02-17', error: null })

    expect(normalizeConfigValue('DATE_LIST', '2026-13-01').error).toContain('无效日期')
    expect(normalizeConfigValue('DATE_LIST', '2026-02-30').error).toContain('2026-02-30')
    expect(normalizeConfigValue('DATE_LIST', '2026/01/01').ok).toBe(false)
    expect(normalizeConfigValue('DATE_LIST', '').error).toContain('至少填写一个日期')
  })

  test('isValidIsoDate 处理闰年与非法日期', () => {
    expect(isValidIsoDate('2024-02-29')).toBe(true)
    expect(isValidIsoDate('2026-02-29')).toBe(false)
    expect(isValidIsoDate('2026-04-31')).toBe(false)
    expect(isValidIsoDate('2026-1-1')).toBe(false)
  })
})

describe('STRING 与占位提示', () => {
  test('STRING 只做 trim，允许为空', () => {
    expect(normalizeConfigValue('STRING', '  工资保密  ').value).toBe('工资保密')
    expect(normalizeConfigValue('STRING', '').ok).toBe(true)
  })

  test('每种类型都有输入提示', () => {
    expect(configInputPlaceholder('DECIMAL')).toContain('21.75')
    expect(configInputPlaceholder('DATE_LIST')).toContain('2026-01-01')
    expect(configInputPlaceholder('STRING')).toContain('参数值')
  })
})
