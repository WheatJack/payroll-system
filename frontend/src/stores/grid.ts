import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  ChangesetRequest,
  ChangesetResult,
  EmployeeType,
  GridColumn,
  GridPeriod,
  GridResponse,
  GridRow,
} from '@/api/models'
import { buildChangeset } from '@/utils/changeset'
import { eq, round } from '@/utils/money'

export interface GridFilter {
  deptId?: string
  employeeType?: EmployeeType
  jobLevel?: string
  keyword?: string
}

type DirtyMap = Record<string, Record<string, string>>

function sameAmount(a: string, b: string): boolean {
  if (a === b) return true
  try {
    return eq(a, b)
  } catch {
    return false
  }
}

/**
 * 编制台状态。
 *
 * 关键约束：dirty（未保存编辑）与筛选视图**完全解耦**——
 * 切换/清除筛选不会丢编辑，返回全部行时仍能看到未保存的改动。
 */
export const useGridStore = defineStore('grid', () => {
  const period = ref<GridPeriod | null>(null)
  const columns = ref<GridColumn[]>([])
  const rows = ref<GridRow[]>([])
  const versions = ref<Record<string, number>>({})
  const dirty = ref<DirtyMap>({})
  const filter = ref<GridFilter>({})
  const conflictIds = ref<string[]>([])
  /** 当前数据属于哪个账号（userId）；换账号必须重新拉，禁止复用上一个人的工资行 */
  const ownerUserId = ref<string>('')
  const loading = ref(false)
  const saving = ref(false)

  const rowIndex = computed(() => {
    const map = new Map<string, GridRow>()
    for (const row of rows.value) map.set(row.recordId, row)
    return map
  })

  const editableColumns = computed(() => columns.value.filter((column) => column.editable))

  function load(data: GridResponse, owner = ''): void {
    period.value = data.period
    columns.value = data.columns.map((column) => ({ ...column }))
    rows.value = data.rows.map((row) => ({ ...row, items: { ...row.items } }))
    const nextVersions: Record<string, number> = {}
    for (const row of data.rows) nextVersions[row.recordId] = row.version
    versions.value = nextVersions
    dirty.value = {}
    conflictIds.value = []
    ownerUserId.value = owner
  }

  /** 当前缓存是否属于这个账号（跨账号复用时必须重新加载）。 */
  function belongsTo(userId: string | null | undefined): boolean {
    return ownerUserId.value !== '' && ownerUserId.value === (userId ?? '')
  }

  function versionOf(recordId: string): number {
    return versions.value[recordId] ?? rowIndex.value.get(recordId)?.version ?? 0
  }

  function originalValue(recordId: string, itemCode: string): string {
    return rowIndex.value.get(recordId)?.items?.[itemCode] ?? '0.00'
  }

  /** 当前展示值：dirty 优先，其次原始值，缺失按 0.00。 */
  function valueOf(recordId: string, itemCode: string): string {
    const rowDirty = dirty.value[recordId]
    if (rowDirty && rowDirty[itemCode] !== undefined) return rowDirty[itemCode]
    return originalValue(recordId, itemCode)
  }

  function isDirty(recordId: string, itemCode: string): boolean {
    return dirty.value[recordId]?.[itemCode] !== undefined
  }

  function rowDirtyCount(recordId: string): number {
    return Object.keys(dirty.value[recordId] ?? {}).length
  }

  /** 该工资项的列精度；未加载该列时退回 2 位，绝不硬编码 2。 */
  function scaleOf(itemCode: string): number {
    return columns.value.find((column) => column.itemCode === itemCode)?.scale ?? 2
  }

  /**
   * 记录一次编辑；按**该列的 scale** 做一次 HALF_UP 舍入（与后端 precision_scale 一致），
   * 编辑回原值时自动从 dirty 中移除。
   *
   * @param scale 显式指定精度（一般不需要，默认取 columns 里的 scale）
   */
  function edit(recordId: string, itemCode: string, amount: string, scale?: number): void {
    const precision = scale ?? scaleOf(itemCode)
    let normalized: string
    try {
      normalized = round(amount, precision)
    } catch {
      // 非法金额不写进 dirty（由调用方提示），避免污染 changeset
      return
    }

    const nextRow: Record<string, string> = { ...(dirty.value[recordId] ?? {}) }
    if (sameAmount(originalValue(recordId, itemCode), normalized)) {
      delete nextRow[itemCode]
    } else {
      nextRow[itemCode] = normalized
    }
    const nextAll: DirtyMap = { ...dirty.value }
    if (Object.keys(nextRow).length === 0) {
      delete nextAll[recordId]
    } else {
      nextAll[recordId] = nextRow
    }
    dirty.value = nextAll
  }

  function revert(recordId: string, itemCode: string): void {
    const nextRow = { ...(dirty.value[recordId] ?? {}) }
    delete nextRow[itemCode]
    const nextAll: DirtyMap = { ...dirty.value }
    if (Object.keys(nextRow).length === 0) delete nextAll[recordId]
    else nextAll[recordId] = nextRow
    dirty.value = nextAll
  }

  function revertAll(): void {
    dirty.value = {}
    conflictIds.value = []
  }

  function setFilter(next: GridFilter): void {
    filter.value = { ...filter.value, ...next }
  }

  function clearFilter(): void {
    filter.value = {}
  }

  const visibleRows = computed(() => {
    const current = filter.value
    return rows.value.filter((row) => {
      if (current.deptId && row.deptId !== current.deptId) return false
      if (current.employeeType && row.employeeType !== current.employeeType) return false
      if (current.jobLevel && row.jobLevel !== current.jobLevel) return false
      if (current.keyword) {
        const keyword = current.keyword.trim().toLowerCase()
        const empNo = String(row.empNo ?? '').toLowerCase()
        const empName = String(row.empName ?? '').toLowerCase()
        if (!empNo.includes(keyword) && !empName.includes(keyword)) {
          return false
        }
      }
      return true
    })
  })

  const dirtyCount = computed(() =>
    Object.values(dirty.value).reduce((total, items) => total + Object.keys(items).length, 0),
  )
  const dirtyRowCount = computed(() => Object.keys(dirty.value).length)
  const hasChanges = computed(() => dirtyCount.value > 0)

  /** 当前表格里出现过的职级，供筛选下拉使用（不写死职级字典）。 */
  const jobLevelOptions = computed(() => {
    const levels = new Set<string>()
    for (const row of rows.value) {
      if (row.jobLevel) levels.add(row.jobLevel)
    }
    return [...levels].sort()
  })

  /** 组装 changeset：每个有改动的行一条 change，带原始 version。 */
  function buildRequest(): ChangesetRequest {
    const changes: ChangesetRequest['changes'] = []
    for (const row of rows.value) {
      const rowDirty = dirty.value[row.recordId]
      if (!rowDirty || Object.keys(rowDirty).length === 0) continue
      const original = row.items ?? {}
      const current = { ...original, ...rowDirty }
      const request = buildChangeset(original, current, versionOf(row.recordId), row.recordId)
      changes.push(...request.changes)
    }
    return { changes }
  }

  /** 保存成功后用服务端返回行刷新（新 version、重算后的合计），并清掉已保存的 dirty。 */
  function applySaveResult(result: ChangesetResult): void {
    const returned = new Map(result.rows.map((row) => [row.recordId, row]))
    rows.value = rows.value.map((row) => {
      const next = returned.get(row.recordId)
      return next ? { ...next, items: { ...next.items } } : row
    })
    for (const row of result.rows) {
      versions.value = { ...versions.value, [row.recordId]: row.version }
      const nextAll: DirtyMap = { ...dirty.value }
      delete nextAll[row.recordId]
      dirty.value = nextAll
    }
    conflictIds.value = []
  }

  function markConflicts(ids: string[]): void {
    conflictIds.value = [...new Set(ids)]
  }

  function isConflict(recordId: string): boolean {
    return conflictIds.value.includes(recordId)
  }

  function reset(): void {
    period.value = null
    columns.value = []
    rows.value = []
    versions.value = {}
    dirty.value = {}
    filter.value = {}
    conflictIds.value = []
    ownerUserId.value = ''
  }

  return {
    period,
    columns,
    rows,
    versions,
    dirty,
    filter,
    conflictIds,
    ownerUserId,
    loading,
    saving,
    rowIndex,
    editableColumns,
    jobLevelOptions,
    visibleRows,
    dirtyCount,
    dirtyRowCount,
    hasChanges,
    load,
    belongsTo,
    versionOf,
    originalValue,
    valueOf,
    isDirty,
    rowDirtyCount,
    scaleOf,
    edit,
    revert,
    revertAll,
    setFilter,
    clearFilter,
    buildRequest,
    applySaveResult,
    markConflicts,
    isConflict,
    reset,
  }
})
