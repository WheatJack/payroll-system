<script setup lang="ts">
/**
 * 月度汇总报表。
 *
 * 数据范围与「仅统计 LOCKED/ARCHIVED」全部由后端处理，前端只负责展示与合计。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Search } from '@element-plus/icons-vue'
import * as reportApi from '@/api/report'
import { isApiError } from '@/api/client'
import { saveBlob } from '@/api/download'
import type { MonthlyReportRow } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import { useOrgOptions } from '@/composables/useOrgOptions'
import { format, formatOrZero } from '@/utils/money'
import { sumCountColumn, sumMoneyColumn, validateMonthlyFilters } from '@/utils/report'
import { buildMonthlyParams } from '@/utils/export'
import { createLatestOnly } from '@/utils/latest'

const auth = useAuthStore()
const canExport = computed(() => auth.has('payroll:export'))
const isGroupScope = computed(() => auth.user?.dataScope === 'ALL')

const { companies, deptTree, loadCompanies, loadDepts } = useOrgOptions()

/** 查询防竞态：旧响应后到不得覆盖新结果。 */
const latest = createLatestOnly()

/** 默认取上个月：报表只统计已锁定期间，当月通常还没锁。 */
function previousMonth(): string {
  const now = new Date()
  const year = now.getMonth() === 0 ? now.getFullYear() - 1 : now.getFullYear()
  const month = now.getMonth() === 0 ? 12 : now.getMonth()
  return `${year}-${String(month).padStart(2, '0')}`
}

const filters = ref({
  periodYm: previousMonth(),
  // 集团数据范围默认不限定主体（避免悄悄只看到本公司）；其它范围默认本公司
  companyId: isGroupScope.value ? '' : auth.companyId || '',
  deptId: '',
})

const rows = ref<MonthlyReportRow[]>([])
const loading = ref(false)
const exporting = ref(false)
const loadError = ref('')

const hasRows = computed(() => rows.value.length > 0)

async function query() {
  const invalid = validateMonthlyFilters(filters.value)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  const token = latest.next()
  loading.value = true
  loadError.value = ''
  try {
    const data = await reportApi.getMonthlyReport({
      periodYm: filters.value.periodYm,
      ...(filters.value.companyId ? { companyId: filters.value.companyId } : {}),
      ...(filters.value.deptId ? { deptId: filters.value.deptId } : {}),
    })
    if (!latest.isLatest(token)) return
    rows.value = data
  } catch (error) {
    if (!latest.isLatest(token)) return
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '月度汇总加载失败'
    ElMessage.error(loadError.value)
  } finally {
    if (latest.isLatest(token)) loading.value = false
  }
}

const totals = computed(() => ({
  employeeCount: sumCountColumn(rows.value, (row) => row.employeeCount),
  grossAmount: sumMoneyColumn(rows.value, (row) => row.grossAmount),
  deductionAmount: sumMoneyColumn(rows.value, (row) => row.deductionAmount),
  netAmount: sumMoneyColumn(rows.value, (row) => row.netAmount),
}))

/** 合计行由 money 计算（不使用组件内置 sum）。 */
function summaryMethod({ columns }: { columns: Array<{ property?: string }> }): string[] {
  return columns.map((column, index) => {
    if (index === 0) return '合计'
    switch (column.property) {
      case 'employeeCount':
        return String(totals.value.employeeCount)
      case 'grossAmount':
        return format(totals.value.grossAmount)
      case 'deductionAmount':
        return format(totals.value.deductionAmount)
      case 'netAmount':
        return format(totals.value.netAmount)
      default:
        return ''
    }
  })
}

async function exportReport() {
  const invalid = validateMonthlyFilters(filters.value)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  exporting.value = true
  try {
    const file = await reportApi.exportReport(
      {
        reportType: 'MONTHLY',
        params: buildMonthlyParams(filters.value.periodYm, filters.value.companyId, filters.value.deptId),
        format: 'XLSX',
      },
      `monthly-${filters.value.periodYm}.xlsx`,
    )
    saveBlob(file.blob, file.filename)
    ElMessage.success('导出已开始下载')
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

watch(
  () => filters.value.companyId,
  (companyId) => {
    filters.value.deptId = ''
    void loadDepts(companyId)
  },
)

onMounted(() => {
  void loadCompanies()
  void loadDepts(filters.value.companyId)
  void query()
})
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-date-picker
          v-model="filters.periodYm"
          type="month"
          value-format="YYYY-MM"
          placeholder="工资年月"
          style="width: 150px"
        />
        <el-select v-model="filters.companyId" clearable placeholder="法人主体" style="width: 170px">
          <el-option v-for="company in companies" :key="company.id" :value="company.id" :label="company.companyName" />
        </el-select>
        <el-tree-select
          v-model="filters.deptId"
          :data="deptTree"
          check-strictly
          clearable
          placeholder="部门（可选）"
          style="width: 180px"
          :render-after-expand="false"
        />
        <el-button type="primary" :icon="Search" @click="query">查询</el-button>
        <span class="muted">仅统计已锁定/已归档期间；数据范围由后端过滤</span>
      </div>      <div class="toolbar-actions">
        <el-button v-if="canExport" :icon="Download" :loading="exporting" @click="exportReport">导出 xlsx</el-button>
      </div>
    </div>

    <el-alert
      v-if="isGroupScope && !filters.companyId"
      title="当前账号是集团数据范围：未选择法人主体时将汇总数据范围内全部主体"
      type="info"
      show-icon
      :closable="false"
      class="list-error"
    />
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="list-error" />
    <el-alert
      v-if="!loading && !loadError && rows.length === 0"
      title="仅统计已锁定（LOCKED）与已归档期间；所选期间尚未锁定时为空属正常"
      type="info"
      show-icon
      :closable="false"
      class="list-error"
    />

    <el-table
      v-loading="loading"
      :data="rows"
      border
      stripe
      show-summary
      :summary-method="summaryMethod"
      empty-text="该期间没有可统计的已锁定数据"
    >
      <el-table-column prop="periodYm" label="期间" width="110" />
      <el-table-column label="部门" min-width="160">
        <template #default="{ row }">{{ row.deptName || row.deptId }}</template>
      </el-table-column>
      <el-table-column prop="employeeCount" label="人数" width="90" align="right" />
      <el-table-column prop="grossAmount" label="应发合计" width="160" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.grossAmount) }}</span></template>
      </el-table-column>
      <el-table-column prop="deductionAmount" label="扣款合计" width="160" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.deductionAmount) }}</span></template>
      </el-table-column>
      <el-table-column prop="netAmount" label="实发合计" width="160" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.netAmount) }}</span></template>
      </el-table-column>
      <template #empty>
        <el-empty :description="hasRows ? '' : '该期间没有可统计的已锁定数据'" :image-size="90" />
      </template>
    </el-table>
  </div>
</template>

<style scoped>
.list-error {
  margin-bottom: 12px;
}
.amount-cell {
  font-variant-numeric: tabular-nums;
}
</style>
