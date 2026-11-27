import { describe, expect, test } from 'vitest'
import { footerTotalLabel, isGridFilterActive } from '../grid'

describe('编制台筛选态与表尾标签', () => {
  test('职级筛选也算筛选态（N-5 回归）', () => {
    expect(isGridFilterActive({ jobLevel: 'P4' })).toBe(true)
  })

  test('部门/员工类型/关键字（去空白后）都算', () => {
    expect(isGridFilterActive({ deptId: '1' })).toBe(true)
    expect(isGridFilterActive({ employeeType: 'PROBATION' })).toBe(true)
    expect(isGridFilterActive({ keyword: 'BJ0000' })).toBe(true)
  })

  test('空筛选（含纯空白关键字）不算', () => {
    expect(isGridFilterActive({})).toBe(false)
    expect(isGridFilterActive({ keyword: '   ' })).toBe(false)
    expect(isGridFilterActive(null)).toBe(false)
  })

  test('表尾标签：筛选时「合计*」防止窄列折行，否则「合计」', () => {
    expect(footerTotalLabel(true)).toBe('合计*')
    expect(footerTotalLabel(false)).toBe('合计')
  })
})
