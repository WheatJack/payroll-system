<script setup lang="ts">
/**
 * 专项附加扣除（人员抽屉内）。
 * deductionType 是枚举，界面一律中文展示；金额走 MoneyInput/money。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import * as insuranceApi from '@/api/insurance'
import { isApiError } from '@/api/client'
import type { DeductionType, SpecialDeduction } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import MoneyInput from '@/components/MoneyInput.vue'
import { coerce, format, formatOrZero } from '@/utils/money'
import { DEDUCTION_TYPE_LABELS, deductionTypeLabel } from '@/utils/tax'

const props = defineProps<{ employeeId: string }>()
const auth = useAuthStore()
const canEdit = computed(() => auth.has('payroll:record:edit'))

const DEDUCTION_TYPES = Object.keys(DEDUCTION_TYPE_LABELS) as DeductionType[]

const rows = ref<SpecialDeduction[]>([])
const loading = ref(false)
const loadError = ref('')

const dialogVisible = ref(false)
const submitting = ref(false)
const formError = ref('')

const form = reactive({
  deductionType: 'CHILD_EDU' as DeductionType,
  monthlyAmount: '0.00',
  effectiveFrom: '',
  effectiveTo: '',
  remark: '',
})

function defaultEffectiveFrom(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`
}

async function load() {
  if (!props.employeeId) return
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await insuranceApi.listSpecialDeductions(props.employeeId)
  } catch (error) {
    console.error('[payroll] 专项附加扣除加载失败', error)
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '专项附加扣除加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.deductionType = 'CHILD_EDU'
  form.monthlyAmount = '0.00'
  form.effectiveFrom = defaultEffectiveFrom()
  form.effectiveTo = ''
  form.remark = ''
  formError.value = ''
  dialogVisible.value = true
}

async function submit() {
  formError.value = ''
  const amount = coerce(form.monthlyAmount)
  if (amount === null) {
    formError.value = '月额需为合法金额'
    return
  }
  if (!form.effectiveFrom) {
    formError.value = '请选择生效日期'
    return
  }
  submitting.value = true
  try {
    await insuranceApi.createSpecialDeduction(props.employeeId, {
      deductionType: form.deductionType,
      monthlyAmount: amount,
      effectiveFrom: form.effectiveFrom,
      effectiveTo: form.effectiveTo || null,
      remark: form.remark || null,
    })
    ElMessage.success('专项附加扣除已新增')
    dialogVisible.value = false
    await load()
  } catch (error) {
    formError.value = isApiError(error) ? error.message : '保存失败，请稍后重试'
    ElMessage.error(formError.value)
  } finally {
    submitting.value = false
  }
}

async function remove(row: SpecialDeduction) {
  try {
    await ElMessageBox.confirm(
      `确认删除「${deductionTypeLabel(row.deductionType)}」这条专项附加扣除？`,
      '删除专项附加扣除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await insuranceApi.deleteSpecialDeduction(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '删除失败')
  }
}

watch(() => props.employeeId, load, { immediate: true })
defineExpose({ reload: load })
</script>

<template>
  <div v-loading="loading" class="deduction-panel">
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="gap" />
    <div class="panel-toolbar">
      <el-button v-if="canEdit" type="primary" size="small" :icon="Plus" @click="openCreate">新增专项附加扣除</el-button>
      <span class="muted">专项附加扣除按月计入累计减除，影响个税累计预扣</span>
    </div>

    <el-table :data="rows" border size="small">
      <el-table-column label="项目" width="140">
        <template #default="{ row }">{{ deductionTypeLabel(row.deductionType) }}</template>
      </el-table-column>
      <el-table-column label="月额" width="130" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ format(row.monthlyAmount) }}</span></template>
      </el-table-column>
      <el-table-column label="生效区间" min-width="200">
        <template #default="{ row }">{{ row.effectiveFrom }} ~ {{ row.effectiveTo ?? '至今' }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column v-if="canEdit" label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无专项附加扣除" :image-size="70" />
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新增专项附加扣除" width="560px" destroy-on-close>
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" class="gap" />
      <el-form label-width="110px">
        <el-form-item label="扣除项目" required>
          <el-select v-model="form.deductionType" style="width: 100%">
            <el-option v-for="type in DEDUCTION_TYPES" :key="type" :value="type" :label="DEDUCTION_TYPE_LABELS[type]" />
          </el-select>
        </el-form-item>
        <el-form-item label="月额" required>
          <MoneyInput v-model="form.monthlyAmount" :scale="2" aria-label="月额" />
        </el-form-item>
        <el-form-item label="生效日期" required>
          <el-date-picker v-model="form.effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="form.effectiveTo" type="date" value-format="YYYY-MM-DD" style="width: 100%" placeholder="留空表示至今" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
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
.gap {
  margin-bottom: 12px;
}
.panel-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.amount-cell {
  font-variant-numeric: tabular-nums;
}
</style>
