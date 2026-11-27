/**
 * 编制台表尾的筛选态判定。
 *
 * N-5 回归点：职级（jobLevel）必须计入筛选态，否则「只按职级筛选」时
 * 合计数值变了、标签却仍是「合计」，用户会以为筛选没生效。
 */
import type { GridFilter } from '@/stores/grid'

export function isGridFilterActive(filter: GridFilter | null | undefined): boolean {
  if (!filter) return false
  return Boolean(
    filter.deptId || filter.employeeType || filter.jobLevel || filter.keyword?.trim(),
  )
}

/**
 * 表尾首列只有 100px，长文案（「合计（筛选后）」）会折行；
 * 筛选态用「合计*」，含义由表格下方说明承载。
 */
export function footerTotalLabel(filterActive: boolean): string {
  return filterActive ? '合计*' : '合计'
}
