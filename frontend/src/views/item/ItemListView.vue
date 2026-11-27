<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import * as itemApi from '@/api/item'
import { isApiError } from '@/api/client'
import { createLatestOnly } from '@/utils/latest'
import type { PayItem, PayItemCategory, PayItemDataType, PayDirection } from '@/api/models'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canEdit = computed(() => auth.has('payroll:record:edit'))

const CATEGORY_LABELS: Record<PayItemCategory, string> = {
  FIXED: '固定',
  VARIABLE: '变动',
  COMPUTED: '计算',
}

const DATA_TYPE_LABELS: Record<PayItemDataType, string> = {
  AMOUNT: '金额',
  HOURS: '小时',
  DAYS: '天数',
  RATE: '比率',
}

const filters = reactive({ category: '' as PayItemCategory | '', status: '' as 0 | 1 | '' })
const rows = ref<PayItem[]>([])
const loading = ref(false)
const loadError = ref('')
/** 列表请求竞态守卫（I-8） */
const loadGuard = createLatestOnly()

const dialogVisible = ref(false)
const editing = ref<PayItem | null>(null)
const submitting = ref(false)
const formError = ref('')
const form = reactive({
  itemCode: '',
  itemName: '',
  category: 'VARIABLE' as PayItemCategory,
  direction: 1 as PayDirection,
  dataType: 'AMOUNT' as PayItemDataType,
  precisionScale: 2,
  includeInGross: true,
  editable: true,
  importable: true,
  sortNo: 0,
  status: 1 as 0 | 1,
})

const dragId = ref<string | null>(null)
const tableWrap = ref<HTMLElement>()

const companyItems = computed(() => rows.value)

function categoryLabel(category: PayItemCategory): string {
  return CATEGORY_LABELS[category] ?? category
}

function dataTypeLabel(dataType: PayItemDataType): string {
  return DATA_TYPE_LABELS[dataType] ?? dataType
}

async function load() {
  const token = loadGuard.next()
  loading.value = true
  loadError.value = ''
  try {
    const data = await itemApi.listPayItems({
      ...(filters.category ? { category: filters.category } : {}),
      ...(filters.status !== '' ? { status: filters.status } : {}),
    })
    if (!loadGuard.isLatest(token)) return
    rows.value = data
  } catch (error) {
    if (!loadGuard.isLatest(token)) return
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '工资项加载失败'
    ElMessage.error(loadError.value)
  } finally {
    if (loadGuard.isLatest(token)) loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    itemCode: '',
    itemName: '',
    category: 'VARIABLE',
    direction: 1,
    dataType: 'AMOUNT',
    precisionScale: 2,
    includeInGross: true,
    editable: true,
    importable: true,
    sortNo: (companyItems.value.at(-1)?.sortNo ?? 0) + 10,
    status: 1,
  })
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(row: PayItem) {
  editing.value = row
  Object.assign(form, {
    itemCode: row.itemCode,
    itemName: row.itemName,
    category: row.category,
    direction: row.direction,
    dataType: row.dataType,
    precisionScale: row.precisionScale,
    includeInGross: row.includeInGross,
    editable: row.editable,
    importable: row.importable,
    sortNo: row.sortNo,
    status: row.status,
  })
  formError.value = ''
  dialogVisible.value = true
}

async function submit() {
  formError.value = ''
  if (!form.itemCode.trim() || !form.itemName.trim()) {
    formError.value = '科目编码与名称必填'
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      // 类别与方向不在修改请求中提交：已被工资表使用的科目禁止修改（服务端 409 兜底）
      await itemApi.updatePayItem(editing.value.id, {
        itemName: form.itemName.trim(),
        dataType: form.dataType,
        precisionScale: form.precisionScale,
        includeInGross: form.includeInGross,
        editable: form.editable,
        importable: form.importable,
        sortNo: form.sortNo,
        status: form.status,
      })
      ElMessage.success('工资项已保存')
    } else {
      await itemApi.createPayItem({
        itemCode: form.itemCode.trim(),
        itemName: form.itemName.trim(),
        category: form.category,
        direction: form.direction,
        dataType: form.dataType,
        precisionScale: form.precisionScale,
        includeInGross: form.includeInGross,
        editable: form.editable,
        importable: form.importable,
        sortNo: form.sortNo,
        status: form.status,
      })
      ElMessage.success('工资项已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    formError.value = isApiError(error) ? error.message : '保存失败'
    ElMessage.error(formError.value)
  } finally {
    submitting.value = false
  }
}

/* ---------------- 拖拽排序（列顺序 = sortNo） ---------------- */

function onDragStart(row: PayItem) {
  dragId.value = row.id
}

async function onDrop(event: DragEvent) {
  event.preventDefault()
  const sourceId = dragId.value
  dragId.value = null
  if (!sourceId) return

  const tbody = tableWrap.value?.querySelector('.el-table__body tbody')
  const targetRow = (event.target as HTMLElement | null)?.closest('tr')
  if (!tbody || !targetRow || !tbody.contains(targetRow)) return

  const domRows = Array.from(tbody.querySelectorAll('tr'))
  const targetIndex = domRows.indexOf(targetRow as HTMLTableRowElement)
  if (targetIndex < 0) return

  const ordered = [...rows.value]
  const fromIndex = ordered.findIndex((item) => item.id === sourceId)
  if (fromIndex < 0 || fromIndex === targetIndex) return

  const [moved] = ordered.splice(fromIndex, 1)
  ordered.splice(targetIndex, 0, moved)

  const before = new Map(rows.value.map((item) => [item.id, item.sortNo]))
  const changed: PayItem[] = []
  ordered.forEach((item, index) => {
    const nextSortNo = (index + 1) * 10
    if (item.sortNo !== nextSortNo) {
      item.sortNo = nextSortNo
      if (before.get(item.id) !== nextSortNo) changed.push(item)
    }
  })
  rows.value = ordered
  if (changed.length === 0) return

  try {
    await Promise.all(changed.map((item) => itemApi.updatePayItem(item.id, { sortNo: item.sortNo })))
    ElMessage.success('列顺序已保存')
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '列顺序保存失败')
    await load()
  }
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-select v-model="filters.category" clearable placeholder="类别" style="width: 120px" @change="load">
          <el-option value="FIXED" label="固定" />
          <el-option value="VARIABLE" label="变动" />
          <el-option value="COMPUTED" label="计算" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="状态" style="width: 120px" @change="load">
          <el-option :value="1" label="启用" />
          <el-option :value="0" label="停用" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <span class="muted drag-hint">拖拽行首 ⋮⋮ 可调整工资表列顺序</span>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openCreate">新增工资项</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="list-error" />

    <div ref="tableWrap" @dragover.prevent @drop="onDrop">
      <el-table v-loading="loading" :data="rows" row-key="id" border stripe size="default">
        <el-table-column width="46">
          <template #default="{ row }">
            <span v-if="canEdit" class="drag-handle" draggable="true" title="拖拽排序" @dragstart="onDragStart(row)">⋮⋮</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="itemCode" label="科目编码" width="150" />
        <el-table-column prop="itemName" label="科目名称" min-width="150" />
        <el-table-column label="类别" width="90">
          <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
        </el-table-column>
        <el-table-column label="方向" width="90">
          <template #default="{ row }">
            <el-tag :type="row.direction === 1 ? 'success' : 'danger'" size="small">
              {{ row.direction === 1 ? '增加' : '扣款' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据类型" width="90">
          <template #default="{ row }">{{ dataTypeLabel(row.dataType) }}</template>
        </el-table-column>
        <el-table-column prop="precisionScale" label="精度" width="70" />
        <el-table-column label="计入应发" width="90">
          <template #default="{ row }">{{ row.includeInGross ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="可编辑" width="80">
          <template #default="{ row }">{{ row.editable ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="可导入" width="80">
          <template #default="{ row }">{{ row.importable ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="sortNo" label="排序号" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canEdit" link type="primary" @click="openEdit(row)">编辑</el-button>
            <span v-else class="muted">只读</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无工资项" :image-size="90" />
        </template>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑工资项' : '新增工资项'"
      width="560px"
      destroy-on-close
    >
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" class="form-error" />
      <el-form label-width="96px">
        <el-form-item label="科目编码" required>
          <el-input v-model="form.itemCode" :disabled="!!editing" maxlength="32" placeholder="如 PERF_BONUS" />
          <div v-if="editing" class="field-hint">编码创建后不可修改</div>
        </el-form-item>
        <el-form-item label="科目名称" required>
          <el-input v-model="form.itemName" maxlength="64" />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category" :disabled="!!editing" style="width: 100%">
            <el-option value="FIXED" label="固定" />
            <el-option value="VARIABLE" label="变动" />
            <el-option value="COMPUTED" label="计算" />
          </el-select>
        </el-form-item>
        <el-form-item label="方向">
          <el-select v-model="form.direction" :disabled="!!editing" style="width: 100%">
            <el-option :value="1" label="增加（收入）" />
            <el-option :value="2" label="减少（扣款）" />
          </el-select>
          <div v-if="editing" class="field-hint">已被工资表使用的科目禁止修改类别与方向（服务端仍会校验并返回 409）</div>
        </el-form-item>
        <el-form-item label="数据类型">
          <el-select v-model="form.dataType" style="width: 100%">
            <el-option value="AMOUNT" label="金额" />
            <el-option value="HOURS" label="小时" />
            <el-option value="DAYS" label="天数" />
            <el-option value="RATE" label="比率" />
          </el-select>
        </el-form-item>
        <el-form-item label="精度">
          <el-input-number v-model="form.precisionScale" :min="0" :max="4" :step="1" />
          <span class="field-hint inline">小数位数（金额一般为 2）</span>
        </el-form-item>
        <el-form-item label="计入应发">
          <el-switch v-model="form.includeInGross" />
        </el-form-item>
        <el-form-item label="可编辑">
          <el-switch v-model="form.editable" />
        </el-form-item>
        <el-form-item label="可导入">
          <el-switch v-model="form.importable" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="form.sortNo" :min="0" :step="10" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.list-error,
.form-error {
  margin-bottom: 12px;
}
.drag-hint {
  font-size: 12px;
}
.drag-handle {
  cursor: grab;
  color: #a8abb2;
  user-select: none;
  letter-spacing: -2px;
}
.drag-handle:active {
  cursor: grabbing;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  line-height: 18px;
  margin-top: 2px;
}
.field-hint.inline {
  margin-left: 8px;
  margin-top: 0;
}
</style>
