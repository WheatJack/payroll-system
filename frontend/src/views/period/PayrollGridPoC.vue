<script setup lang="ts">
/**
 * vxe-table 编制台 PoC（Task 15 决策点）
 *
 * 用 500 行 × 12 个动态列验证四项能力：
 * 1) 列头完全由接口 `columns` 驱动（不写死任何工资项）
 * 2) 仅 `editable=true` 的列可编辑（双击进入编辑）
 * 3) 底部合计行用 money.add 计算，且按 direction 分别汇总应发/扣款/实发（R9）
 * 4) Excel 多行多列粘贴进选中区域
 *
 * 数据来源：docs/api/examples/grid-response.json（截断示例，只有 2 列），
 * PoC 在其基础上扩到 12 列 × 500 行；渲染逻辑仍然只看 `columns`。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import exampleRaw from '../../../../docs/api/examples/grid-response.json?raw'
import type { GridColumn, GridResponse } from '@/api/models'
import MoneyInput from '@/components/MoneyInput.vue'
import { add, coerce, format, round, sub } from '@/utils/money'
import { parseClipboardMatrix } from '@/utils/paste'

interface Envelope {
  code: number
  msg: string
  data: GridResponse
}

const example = (JSON.parse(exampleRaw) as Envelope).data

const ROW_COUNT = 500
const COLUMN_COUNT = 12

type VmRow = Record<string, unknown> & {
  recordId: string
  version: number
  empNo: string
  empName: string
  deptName: string
}

/** 固定信息列，工资项列由 columns 动态追加。 */
const META_COLUMNS = [
  { field: 'empNo', title: '工号', width: 100, fixed: 'left' as const },
  { field: 'empName', title: '姓名', width: 100, fixed: 'left' as const },
  { field: 'deptName', title: '部门', width: 140 },
]
const META_COLUMN_COUNT = META_COLUMNS.length

const columns = computed<GridColumn[]>(() => {
  const result: GridColumn[] = example.columns.map((column) => ({ ...column }))
  while (result.length < COLUMN_COUNT) {
    const index = result.length + 1
    result.push({
      itemCode: `POC_ITEM_${String(index).padStart(2, '0')}`,
      itemName: `示例项目 ${index}`,
      category: 'VARIABLE',
      direction: index % 4 === 0 ? 2 : 1,
      scale: 2,
      editable: index % 3 !== 0,
    })
  }
  return result.slice(0, COLUMN_COUNT)
})

const editableColumns = computed(() => columns.value.filter((column) => column.editable))

function initialAmount(column: GridColumn, rowIndex: number): string {
  // 全部用字符串金额与 money 运算构造测试数据
  const base = column.direction === 2 ? '50.00' : add('3000.00', String((rowIndex % 37) * 10 + 1))
  return round(add(base, `${rowIndex % 7}.25`), column.scale)
}

function buildRows(): VmRow[] {
  const rows: VmRow[] = []
  for (let index = 0; index < ROW_COUNT; index += 1) {
    const row: VmRow = {
      recordId: String(101 + index),
      version: index % 3,
      empNo: `E${String(index + 1).padStart(4, '0')}`,
      empName: `员工${index + 1}`,
      deptName: index % 2 === 0 ? '北京研发部' : '上海财务部',
    }
    for (const column of columns.value) {
      row[column.itemCode] = initialAmount(column, index)
    }
    rows.push(row)
  }
  return rows
}

const viewRows = ref<VmRow[]>(buildRows())
const tableRef = ref()
const tableWrap = ref<HTMLElement>()
const totalsVersion = ref(0)

/* ------------------------- 合计（R9：按方向分别汇总） ------------------------- */

const totals = computed(() => {
  void totalsVersion.value
  const perColumn: Record<string, string> = {}
  for (const column of columns.value) {
    perColumn[column.itemCode] = viewRows.value.reduce(
      (acc, row) => add(acc, typeof row[column.itemCode] === 'string' ? (row[column.itemCode] as string) : '0.00'),
      '0.00',
    )
  }
  const grossColumns = columns.value.filter((column) => column.direction === 1)
  const deductionColumns = columns.value.filter((column) => column.direction === 2)
  const gross = grossColumns.reduce((acc, column) => add(acc, perColumn[column.itemCode]), '0.00')
  const deduction = deductionColumns.reduce((acc, column) => add(acc, perColumn[column.itemCode]), '0.00')
  return { perColumn, gross, deduction, net: sub(gross, deduction) }
})

/** 底部合计行：第一行逐列合计，第二行应发/扣款/实发汇总（金额均由 money.add 得出）。 */
function footerMethod({ columns: footerColumns }: { columns: Array<{ field?: string }> }): string[][] {
  const perColumnRow = footerColumns.map((column, index) => {
    if (index === 0) return '合计'
    const field = column.field
    if (field && totals.value.perColumn[field] !== undefined) {
      return format(totals.value.perColumn[field])
    }
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

function refreshTotals() {
  totalsVersion.value += 1
  void nextTick(() => tableRef.value?.updateFooter?.())
}

/* --------------------------- 单元格编辑 --------------------------- */

function cellValue(row: VmRow, field: string): string {
  const value = row[field]
  return typeof value === 'string' ? value : '0.00'
}

function onCellEdited(row: VmRow, column: GridColumn) {
  const raw = cellValue(row, column.itemCode)
  const cleaned = coerce(raw)
  if (cleaned === null) {
    ElMessage.warning(`${column.itemName} 不是合法金额，已回退为 0.00`)
    row[column.itemCode] = round('0', column.scale)
  } else {
    row[column.itemCode] = round(cleaned, column.scale)
  }
  refreshTotals()
}

/* --------------------- 区域选择 + Excel 多行粘贴 --------------------- */

interface CellRef {
  rowIndex: number
  colIndex: number
}

interface SelectionRange {
  top: number
  bottom: number
  left: number
  right: number
}

const anchor = ref<CellRef | null>(null)
const focusCell = ref<CellRef | null>(null)

const selection = computed<SelectionRange | null>(() => {
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
  if ((params.$event as MouseEvent).shiftKey && anchor.value) {
    focusCell.value = cell
  } else {
    anchor.value = cell
    focusCell.value = cell
  }
}

function cellClassName({ rowIndex, columnIndex }: { rowIndex: number; columnIndex: number }): string {
  const range = selection.value
  if (!range) return ''
  const inRange =
    rowIndex >= range.top && rowIndex <= range.bottom && columnIndex >= range.left && columnIndex <= range.right
  return inRange ? 'poc-cell-selected' : ''
}

const pasteCount = ref(0)
const pasteSkipped = ref(0)

function applyMatrix(text: string, range: SelectionRange): { applied: number; skipped: number } {
  const matrix = parseClipboardMatrix(text)
  let applied = 0
  let skipped = 0
  matrix.forEach((cells, rowOffset) => {
    const row = viewRows.value[range.top + rowOffset]
    if (!row) {
      skipped += cells.length
      return
    }
    cells.forEach((raw, colOffset) => {
      const itemIndex = range.left + colOffset - META_COLUMN_COUNT
      const column = columns.value[itemIndex]
      // 越过工资项列、只读列、空单元格、非法数值都算跳过，不静默写脏数据
      if (!column || !column.editable || raw === '') {
        if (raw !== '') skipped += 1
        return
      }
      const amount = coerce(raw)
      if (amount === null) {
        skipped += 1
        return
      }
      row[column.itemCode] = round(amount, column.scale)
      applied += 1
    })
  })
  return { applied, skipped }
}

function handlePaste(event: ClipboardEvent) {
  const range = selection.value
  if (!range) return
  const target = event.target as HTMLElement | null
  if (target && /^(INPUT|TEXTAREA)$/i.test(target.tagName)) return
  const text = event.clipboardData?.getData('text/plain') ?? ''
  if (!text.trim()) return
  event.preventDefault()
  const { applied, skipped } = applyMatrix(text, range)
  pasteCount.value = applied
  pasteSkipped.value = skipped
  refreshTotals()
  ElMessage.success(`已粘贴 ${applied} 个单元格${skipped ? `，跳过 ${skipped} 个（只读/越界/非法）` : ''}`)
}

/* ------------------------------ 自检 ------------------------------ */

const selfCheck = reactive({
  columnsRendered: false,
  columnsDetail: '',
  editableWorks: false,
  editableDetail: '',
  footerMatches: false,
  footerDetail: '',
  pasteApplied: 0,
  pasteDetail: '',
})

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

async function waitFor<T>(probe: () => T | null | undefined, timeout = 3000): Promise<T | null> {
  const started = Date.now()
  for (;;) {
    const value = probe()
    if (value) return value
    if (Date.now() - started > timeout) return null
    await sleep(50)
  }
}

async function runSelfCheck() {
  await nextTick()
  const root = tableWrap.value
  if (!root) return

  // vxe-table 渲染是异步的，等表头真正落到 DOM 再检查
  const headers = await waitFor(() => {
    const cells = root.querySelectorAll('.vxe-header--column')
    return cells.length > 0 ? cells : null
  })

  // 1) 动态列头：所有工资项列名都来自 columns，且表头单元格数量覆盖全部列
  const headerTexts = headers ? Array.from(headers).map((cell) => cell.textContent?.trim() ?? '') : []
  const titlesMatched = columns.value.every((column) => headerTexts.some((text) => text.includes(column.itemName)))
  selfCheck.columnsRendered = titlesMatched && headerTexts.length >= columns.value.length
  selfCheck.columnsDetail = `表头 ${headerTexts.length} 个单元格（固定列会有副本），${columns.value.length} 个工资项列名全部命中：${titlesMatched}；可编辑 ${editableColumns.value.length} 列，列头由 columns 驱动、无硬编码`

  // 2) 可编辑单元格：只读列无法进入编辑，editable 列能出现输入框
  const editableColumn = columns.value.find((column) => column.editable)
  const readonlyColumn = columns.value.find((column) => !column.editable)
  const firstRow = viewRows.value[0]
  if (editableColumn && firstRow) {
    tableRef.value?.setEditCell?.(firstRow, editableColumn.itemCode)
    const editableInput = await waitFor(() => root.querySelector('.vxe-body--column.col--edit input'))
    tableRef.value?.clearEdit?.()
    await sleep(80)

    const beforeCount = root.querySelectorAll('.vxe-body--column input').length
    let readonlyBlocked = true
    if (readonlyColumn) {
      tableRef.value?.setEditCell?.(firstRow, readonlyColumn.itemCode)
      await sleep(250)
      readonlyBlocked = root.querySelectorAll('.vxe-body--column input').length <= beforeCount
      tableRef.value?.clearEdit?.()
    }

    selfCheck.editableWorks = !!editableInput && readonlyBlocked
    selfCheck.editableDetail = `「${editableColumn.itemName}」编辑态出现输入框：${!!editableInput}；只读列「${readonlyColumn?.itemName ?? '—'}」无法进入编辑：${readonlyBlocked}；col--edit 单元格 ${root.querySelectorAll('.col--edit').length} 个`
  } else {
    selfCheck.editableDetail = '未找到可编辑列'
  }

  // 3) 底部合计（R9：应发只加 direction=1，扣款只加 direction=2，实发 = 应发 - 扣款）
  const footerText = Array.from(root.querySelectorAll('.vxe-footer--row'))
    .map((row) => row.textContent ?? '')
    .join(' ')
  selfCheck.footerMatches =
    footerText.includes(format(totals.value.gross)) &&
    footerText.includes(format(totals.value.deduction)) &&
    footerText.includes(format(totals.value.net)) &&
    Object.values(totals.value.perColumn).every((value) => footerText.includes(format(value)))
  selfCheck.footerDetail = `应发 ${format(totals.value.gross)} / 扣款 ${format(totals.value.deduction)} / 实发 ${format(totals.value.net)}；逐列合计与 footer 文本一致：${selfCheck.footerMatches}（金额由 money.add 计算，未用组件内置求和）`

  // 4) 粘贴（由真实 paste 事件触发，这里只汇报结果）
  selfCheck.pasteApplied = pasteCount.value
  selfCheck.pasteDetail =
    pasteCount.value > 0 ? `最近一次粘贴写入 ${pasteCount.value} 个单元格（跳过 ${pasteSkipped.value}）` : '尚未粘贴'
}

function regenerate() {
  viewRows.value = buildRows()
  anchor.value = null
  focusCell.value = null
  pasteCount.value = 0
  pasteSkipped.value = 0
  refreshTotals()
  void nextTick(runSelfCheck)
}

onMounted(() => {
  document.addEventListener('paste', handlePaste)
  void nextTick(runSelfCheck)
})

onBeforeUnmount(() => {
  document.removeEventListener('paste', handlePaste)
})
</script>

<template>
  <div class="poc-page">
    <div class="poc-header">
      <div>
        <div class="poc-title">vxe-table 编制台 PoC</div>
        <div class="poc-sub">
          {{ viewRows.length }} 行 × {{ columns.length }} 个动态工资项列 + {{ META_COLUMN_COUNT }} 个信息列 ·
          数据形状取自 docs/api/examples/grid-response.json（示例为截断的 2 列，这里扩到 12 列）
        </div>
      </div>
      <div class="poc-actions">
        <el-button @click="regenerate">重新生成 500 行</el-button>
        <el-button type="primary" @click="runSelfCheck">运行自检</el-button>
      </div>
    </div>

    <div class="check-strip">
      <el-tag :type="selfCheck.columnsRendered ? 'success' : 'info'" size="small">
        1 动态列头 {{ selfCheck.columnsRendered ? 'PASS' : '待验证' }}
      </el-tag>
      <span class="check-detail">{{ selfCheck.columnsDetail }}</span>
      <el-tag :type="selfCheck.editableWorks ? 'success' : 'info'" size="small">
        2 可编辑 {{ selfCheck.editableWorks ? 'PASS' : '待验证' }}
      </el-tag>
      <span class="check-detail">{{ selfCheck.editableDetail }}</span>
      <el-tag :type="selfCheck.footerMatches ? 'success' : 'info'" size="small">
        3 合计行 {{ selfCheck.footerMatches ? 'PASS' : '待验证' }}
      </el-tag>
      <span class="check-detail">{{ selfCheck.footerDetail }}</span>
      <el-tag :type="selfCheck.pasteApplied > 0 ? 'success' : 'info'" size="small">
        4 粘贴 {{ selfCheck.pasteApplied > 0 ? 'PASS' : '待验证' }}
      </el-tag>
      <span class="check-detail">{{ selfCheck.pasteDetail }}</span>
    </div>

    <div class="poc-hint">
      单击选中起点 → Shift + 单击选中终点（{{ selectionText }}）→ Ctrl/Cmd + V 粘贴 Excel 多行数据；双击可编辑单元格进入编辑。
    </div>

    <div ref="tableWrap" class="poc-table-wrap" tabindex="0">
      <vxe-table
        ref="tableRef"
        :data="viewRows"
        height="520"
        border
        stripe
        show-footer
        :footer-method="footerMethod"
        :row-config="{ keyField: 'recordId', isHover: true }"
        :edit-config="{ trigger: 'dblclick', mode: 'cell', showIcon: false }"
        :cell-class-name="cellClassName"
        @cell-click="onCellClick"
      >
        <vxe-column
          v-for="meta in META_COLUMNS"
          :key="meta.field"
          :field="meta.field"
          :title="meta.title"
          :width="meta.width"
          :fixed="meta.fixed"
        />
        <vxe-column
          v-for="column in columns"
          :key="column.itemCode"
          :field="column.itemCode"
          :title="`${column.itemName}${column.direction === 2 ? '（扣）' : ''}`"
          :width="150"
          align="right"
          :edit-render="column.editable ? {} : undefined"
        >
          <template #default="{ row }">
            <span class="cell-amount">{{ format(cellValue(row, column.itemCode)) }}</span>
          </template>
          <template v-if="column.editable" #edit="{ row }">
            <MoneyInput
              :model-value="cellValue(row, column.itemCode)"
              :scale="column.scale"
              :aria-label="column.itemName"
              @update:model-value="(value: string) => (row[column.itemCode] = value)"
              @change="() => onCellEdited(row, column)"
            />
          </template>
        </vxe-column>
      </vxe-table>
    </div>
  </div>
</template>

<style scoped>
.poc-page {
  background: #fff;
  border-radius: 8px;
  padding: 14px 16px 18px;
}
.poc-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.poc-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
}
.poc-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.poc-actions {
  display: flex;
  gap: 8px;
}
.check-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  margin: 14px 0 8px;
  padding: 10px 12px;
  background: #f7f9fc;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}
.check-detail {
  font-size: 12px;
  color: #606266;
  margin-right: 10px;
}
.poc-hint {
  font-size: 12px;
  color: #606266;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 10px;
}
.poc-table-wrap {
  outline: none;
}
.poc-table-wrap :deep(.poc-cell-selected) {
  background-color: #ecf5ff !important;
  box-shadow: inset 0 0 0 1px #409eff;
}
.cell-amount {
  font-variant-numeric: tabular-nums;
}
</style>
