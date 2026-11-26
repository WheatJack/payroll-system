<script setup lang="ts">
/**
 * 编制台正式页（Task 16）
 *
 * - 列完全由 GET /periods/{id}/grid 的 `columns` 驱动
 * - dirty 保存在 Pinia store，与筛选视图解耦（切筛选不丢未保存编辑）
 * - 保存走 changeset：只提交改动单元格，带原始 version（乐观锁）
 * - 409：保留 dirty，并按 `data.conflicts[].recordId` 高亮冲突行
 * - 底部合计按 direction 分别汇总（R9），金额全部走 money
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as gridApi from '@/api/grid'
import { listDepts } from '@/api/org'
import { conflictRecordIds, isApiError } from '@/api/client'
import type { EmployeeType, GridColumn, GridRow, PeriodStatus } from '@/api/models'
import MoneyInput from '@/components/MoneyInput.vue'
import { useAuthStore } from '@/stores/auth'
import { useGridStore } from '@/stores/grid'
import { buildDeptTree, employeeTypeLabel, type DeptNode } from '@/utils/labels'
import { add, coerce, format, sub } from '@/utils/money'
import { parseClipboardMatrix } from '@/utils/paste'

const route = useRoute()
const auth = useAuthStore()
const store = useGridStore()

const periodId = computed(() => String(route.params.id ?? ''))

const loading = ref(false)
const loadError = ref('')
const deptTree = ref<DeptNode[]>([])
const tableRef = ref()
const tableWrap = ref<HTMLElement>()

const STATUS_META: Record<PeriodStatus, { label: string; type: 'info' | 'primary' | 'success' | 'warning' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  EDITING: { label: '编制中', type: 'primary' },
  LOCKED: { label: '已锁定', type: 'success' },
  ARCHIVED: { label: '已归档', type: 'warning' },
}

const periodStatus = computed<PeriodStatus>(() => store.period?.status ?? 'EDITING')
const statusMeta = computed(() => STATUS_META[periodStatus.value])
const canSavePermission = computed(() => auth.has('payroll:record:edit'))
const editable = computed(() => periodStatus.value === 'EDITING' && canSavePermission.value)

const META_COLUMNS = [
  { field: 'empNo', title: '工号', width: 100, fixed: 'left' as const },
  { field: 'empName', title: '姓名', width: 100, fixed: 'left' as const },
  { field: 'deptName', title: '部门', width: 140 },
  { field: 'employeeType', title: '类型', width: 80 },
  { field: 'jobLevel', title: '职级', width: 80 },
  { field: 'seniorityYears', title: '工龄', width: 80, align: 'right' as const },
]
const META_COLUMN_COUNT = META_COLUMNS.length

/* ------------------------------ 加载 ------------------------------ */

async function load(options: { keepDirty?: boolean } = {}) {
  if (!periodId.value) return
  loading.value = true
  loadError.value = ''
  try {
    const data = await gridApi.getGrid(periodId.value)
    if (options.keepDirty && store.hasChanges) {
      // 刷新时必须显式确认，避免静默丢改动
      store.load(data)
    } else {
      store.load(data)
    }
  } catch (error) {
    loadError.value = isApiError(error) ? error.message : '工资表加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

async function loadDepts() {
  if (!auth.companyId) return
  try {
    deptTree.value = buildDeptTree(await listDepts(auth.companyId))
  } catch {
    deptTree.value = []
  }
}

async function refresh() {
  if (store.hasChanges) {
    try {
      await ElMessageBox.confirm('刷新会放弃当前未保存的修改，确认刷新？', '刷新工资表', {
        confirmButtonText: '刷新',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  await load()
}

/* ------------------------------ 值读写 ------------------------------ */

function cellValue(row: GridRow, field: string): string {
  return store.valueOf(row.recordId, field)
}

function onCellUpdate(row: GridRow, column: GridColumn, value: string) {
  store.edit(row.recordId, column.itemCode, value)
}

function onEditCommitted(row: GridRow, column: GridColumn) {
  // 精度由 store 按该列的 scale 统一舍入（与后端 precision_scale 一致），这里只做合法性校验，
  // 不在这里二次截断——否则 3/4 位小数的科目会被悄悄压成 2 位。
  if (coerce(cellValue(row, column.itemCode)) === null) {
    ElMessage.warning(`${column.itemName} 不是合法金额，已回退`)
    store.revert(row.recordId, column.itemCode)
  }
  void refreshTotals()
}

/* --------------------------- 合计（R9） --------------------------- */

const totalsVersion = ref(0)

const totals = computed(() => {
  void totalsVersion.value
  const perColumn: Record<string, string> = {}
  const sourceRows = store.visibleRows
  for (const column of store.columns) {
    perColumn[column.itemCode] = sourceRows.reduce(
      (acc, row) => add(acc, store.valueOf(row.recordId, column.itemCode)),
      '0.00',
    )
  }
  const gross = store.columns
    .filter((column) => column.direction === 1)
    .reduce((acc, column) => add(acc, perColumn[column.itemCode]), '0.00')
  const deduction = store.columns
    .filter((column) => column.direction === 2)
    .reduce((acc, column) => add(acc, perColumn[column.itemCode]), '0.00')
  return { perColumn, gross, deduction, net: sub(gross, deduction) }
})

const filterActive = computed(
  () => Boolean(store.filter.deptId || store.filter.employeeType || store.filter.keyword?.trim()),
)

function footerMethod({ columns: footerColumns }: { columns: Array<{ field?: string }> }): string[][] {
  const perColumnRow = footerColumns.map((column, index) => {
    if (index === 0) return filterActive.value ? '合计（筛选后）' : '合计'
    const field = column.field
    if (field && totals.value.perColumn[field] !== undefined) return format(totals.value.perColumn[field])
    return ''
  })
  const summaryRow = footerColumns.map(() => '')
  if (summaryRow.length >= 6) {
    summaryRow[0] = '方向汇总'
    summaryRow[1] = `应发 ${format(totals.value.gross)}`
    summaryRow[2] = `扣款 ${format(totals.value.deduction)}`
    summaryRow[3] = `实发 ${format(totals.value.net)}`
  }
  return [perColumnRow, summaryRow]
}

async function refreshTotals() {
  totalsVersion.value += 1
  await nextTick()
  tableRef.value?.updateFooter?.()
}

/* ------------------------- 区域选择与粘贴 ------------------------- */

interface CellRef {
  rowIndex: number
  colIndex: number
}

const anchor = ref<CellRef | null>(null)
const focusCell = ref<CellRef | null>(null)

const selection = computed(() => {
  if (!anchor.value || !focusCell.value) return null
  return {
    top: Math.min(anchor.value.rowIndex, focusCell.value.rowIndex),
    bottom: Math.max(anchor.value.rowIndex, focusCell.value.rowIndex),
    left: Math.min(anchor.value.colIndex, focusCell.value.colIndex),
    right: Math.max(anchor.value.colIndex, focusCell.value.colIndex),
  }
})

const selectionText = computed(() => {
  const range = selection.value
  if (!range) return '未选择区域'
  return `已选 ${range.bottom - range.top + 1} 行 × ${range.right - range.left + 1} 列`
})

function onCellClick(params: { rowIndex: number; columnIndex: number; $event: Event }) {
  const cell = { rowIndex: params.rowIndex, colIndex: params.columnIndex }
  if ((params.$event as MouseEvent).shiftKey && anchor.value) focusCell.value = cell
  else {
    anchor.value = cell
    focusCell.value = cell
  }
}

function cellClassName({ rowIndex, columnIndex }: { rowIndex: number; columnIndex: number }): string {
  const range = selection.value
  if (!range) return ''
  return rowIndex >= range.top && rowIndex <= range.bottom && columnIndex >= range.left && columnIndex <= range.right
    ? 'grid-cell-selected'
    : ''
}

function rowClassName({ row }: { row: GridRow }): string {
  const classes: string[] = []
  if (store.isConflict(row.recordId)) classes.push('row-conflict')
  else if (store.rowDirtyCount(row.recordId) > 0) classes.push('row-dirty')
  return classes.join(' ')
}

function handlePaste(event: ClipboardEvent) {
  const range = selection.value
  if (!range || !editable.value) return
  const target = event.target as HTMLElement | null
  if (target && /^(INPUT|TEXTAREA)$/i.test(target.tagName)) return
  const text = event.clipboardData?.getData('text/plain') ?? ''
  if (!text.trim()) return
  event.preventDefault()

  const matrix = parseClipboardMatrix(text)
  const visible = store.visibleRows
  let applied = 0
  let skipped = 0
  matrix.forEach((cells, rowOffset) => {
    const row = visible[range.top + rowOffset]
    if (!row) {
      skipped += cells.length
      return
    }
    cells.forEach((raw, colOffset) => {
      const itemIndex = range.left + colOffset - META_COLUMN_COUNT
      const column = store.columns[itemIndex]
      if (!column || !column.editable || raw === '') {
        if (raw !== '') skipped += 1
        return
      }
      const amount = coerce(raw)
      if (amount === null) {
        skipped += 1
        return
      }
      store.edit(row.recordId, column.itemCode, amount)
      applied += 1
    })
  })
  void refreshTotals()
  ElMessage.success(`已粘贴 ${applied} 个单元格${skipped ? `，跳过 ${skipped} 个（只读/越界/非法）` : ''}`)
}

/* ------------------------------ 保存 ------------------------------ */

const saving = computed(() => store.saving)

async function save() {
  if (!editable.value) return
  const request = store.buildRequest()
  if (request.changes.length === 0) {
    ElMessage.info('没有需要保存的修改')
    return
  }
  store.saving = true
  try {
    const result = await gridApi.saveChangeset(periodId.value, request)
    store.applySaveResult(result)
    await refreshTotals()
    ElMessage.success(`已保存 ${result.updated} 行`)
  } catch (error) {
    if (isApiError(error) && error.code === 409) {
      const ids = conflictRecordIds(error)
      if (ids.length > 0) {
        store.markConflicts(ids)
        ElMessage.error(`有 ${ids.length} 行已被他人修改（已高亮），未保存的修改仍保留；请刷新后重试`)
      } else {
        // 期间已锁定/归档等：没有逐行冲突，按 R8 提示
        ElMessage.error(error.message || '数据已被他人修改，请刷新')
      }
    } else {
      ElMessage.error(isApiError(error) ? error.message : '保存失败，请稍后重试')
    }
  } finally {
    store.saving = false
  }
}

/* ------------------------------ 生命周期 ------------------------------ */

watch(periodId, () => {
  store.reset()
  void load()
})

onMounted(() => {
  document.addEventListener('paste', handlePaste)
  // 同一期间再次进入时保留未保存编辑；换期间才重新拉取
  const samePeriod = store.period?.id === periodId.value && store.rows.length > 0
  if (!samePeriod) {
    void load()
  }
  void loadDepts()
})

onBeforeUnmount(() => {
  document.removeEventListener('paste', handlePaste)
})
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-tag :type="statusMeta.type" size="small">{{ statusMeta.label }}</el-tag>
        <span class="period-ym">{{ store.period?.periodYm ?? '—' }}</span>
        <el-tree-select
          :model-value="store.filter.deptId"
          :data="deptTree"
          check-strictly
          clearable
          placeholder="部门"
          style="width: 170px"
          :render-after-expand="false"
          @update:model-value="(value: string) => store.setFilter({ deptId: value || undefined })"
        />
        <el-select
          :model-value="store.filter.employeeType"
          clearable
          placeholder="员工类型"
          style="width: 130px"
          @update:model-value="(value: EmployeeType) => store.setFilter({ employeeType: value || undefined })"
        >
          <el-option value="REGULAR" label="正式" />
          <el-option value="PROBATION" label="试用" />
          <el-option value="INTERN" label="实习" />
          <el-option value="LABOR" label="劳务" />
          <el-option value="OUTSOURCE" label="外包" />
          <el-option value="REHIRE" label="返聘" />
        </el-select>
        <el-input
          :model-value="store.filter.keyword"
          placeholder="工号/姓名"
          clearable
          style="width: 150px"
          @update:model-value="(value: string) => store.setFilter({ keyword: value })"
        />
        <el-button @click="store.clearFilter()">清除筛选</el-button>
        <el-button :icon="Refresh" @click="refresh">刷新</el-button>
      </div>
      <div class="toolbar-actions">
        <span class="dirty-info">
          <template v-if="store.hasChanges">
            <el-tag type="warning" size="small">{{ store.dirtyRowCount }} 行 / {{ store.dirtyCount }} 个单元格未保存</el-tag>
            <el-button link type="info" @click="store.revertAll()">放弃修改</el-button>
          </template>
          <el-tag v-else type="success" size="small">无未保存修改</el-tag>
        </span>
        <el-button type="primary" :disabled="!editable || !store.hasChanges" :loading="saving" @click="save">
          保存修改
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!editable"
      :title="
        periodStatus === 'EDITING'
          ? '当前账号没有 payroll:record:edit 权限，编制台为只读'
          : `期间状态为「${statusMeta.label}」，编制台为只读（写操作需在服务端解锁后进行）`
      "
      type="warning"
      show-icon
      :closable="false"
      class="grid-alert"
    />
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="grid-alert" />
    <el-alert
      v-if="store.conflictIds.length"
      title="检测到并发修改：红色高亮行为冲突行，未保存的修改已保留，请刷新后重试"
      type="error"
      show-icon
      :closable="false"
      class="grid-alert"
    />

    <div class="grid-hint">
      单击选中起点 → Shift + 单击选中终点（{{ selectionText }}）→ Ctrl/Cmd + V 粘贴 Excel 多行数据；双击可编辑单元格进入编辑。
      合计行按方向分别汇总：应发只加 direction=1，扣款只加 direction=2，实发 = 应发 − 扣款。
    </div>

    <div ref="tableWrap" v-loading="loading" class="grid-wrap" tabindex="0">
      <vxe-table
        ref="tableRef"
        :data="store.visibleRows"
        height="calc(100vh - 280px)"
        border
        stripe
        show-footer
        :footer-method="footerMethod"
        :row-config="{ keyField: 'recordId', isHover: true }"
        :edit-config="editable ? { trigger: 'dblclick', mode: 'cell', showIcon: false } : undefined"
        :cell-class-name="cellClassName"
        :row-class-name="rowClassName"
        @cell-click="onCellClick"
      >
        <vxe-column
          v-for="meta in META_COLUMNS"
          :key="meta.field"
          :field="meta.field"
          :title="meta.title"
          :width="meta.width"
          :align="meta.align ?? 'left'"
          :fixed="meta.fixed"
        >
          <template v-if="meta.field === 'employeeType'" #default="{ row }">
            {{ employeeTypeLabel(row.employeeType) }}
          </template>
          <template v-else-if="meta.field === 'seniorityYears'" #default="{ row }">
            <span class="amount-cell">{{ row.seniorityYears }}</span>
          </template>
        </vxe-column>

        <vxe-column
          v-for="column in store.columns"
          :key="column.itemCode"
          :field="column.itemCode"
          :title="`${column.itemName}${column.direction === 2 ? '（扣）' : ''}`"
          :width="150"
          align="right"
          :edit-render="editable && column.editable ? {} : undefined"
        >
          <template #default="{ row }">
            <span class="grid-amount" :class="{ 'is-dirty': store.isDirty(row.recordId, column.itemCode) }">
              {{ format(cellValue(row, column.itemCode)) }}
            </span>
          </template>
          <template v-if="editable && column.editable" #edit="{ row }">
            <MoneyInput
              :model-value="cellValue(row, column.itemCode)"
              :scale="column.scale"
              :aria-label="column.itemName"
              @update:model-value="(value: string) => onCellUpdate(row, column, value)"
              @change="() => onEditCommitted(row, column)"
            />
          </template>
        </vxe-column>

        <template #empty>
          <el-empty description="没有符合条件的数据" :image-size="90" />
        </template>
      </vxe-table>
    </div>
  </div>
</template>

<style scoped>
.period-ym {
  font-weight: 600;
  font-size: 15px;
  color: #1f2d3d;
  font-variant-numeric: tabular-nums;
}
.dirty-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
.grid-alert {
  margin-bottom: 10px;
}
.grid-hint {
  font-size: 12px;
  color: #606266;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 10px;
}
.grid-wrap {
  outline: none;
}
.grid-wrap :deep(.grid-cell-selected) {
  background-color: #ecf5ff !important;
  box-shadow: inset 0 0 0 1px #409eff;
}
.grid-amount {
  font-variant-numeric: tabular-nums;
}
.grid-amount.is-dirty {
  color: #e6a23c;
  font-weight: 600;
}
</style>
