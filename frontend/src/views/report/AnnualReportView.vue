<script setup lang="ts">
/**
 * 年度汇总报表：可按人（EMP）或按部门（DEPT）。
 *
 * 契约：`groupBy=DEPT` 且操作者数据范围为 `ALL`（集团）时 `companyId` 必填，
 * 否则不同主体的同名部门会被合并加总——前端在查询/导出前就拦住并给出原因。
 * 人数是去重人数，年度值不等于各月相加，页面不做「÷12」校验。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Search } from '@element-plus/icons-vue'
import * as reportApi from '@/api/report'
import { isApiError } from '@/api/client'
import { saveBlob } from '@/api/download'
import type { AnnualReportRow, ReportGroupBy } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import { useOrgOptions } from '@/composables/useOrgOptions'
import { format, formatOrZero } from '@/utils/money'
import { sumCountColumn, sumMoneyColumn, validateAnnualFilters } from '@/utils/report'
import { buildAnnualParams } from '@/utils/export'
import { createLatestOnly } from '@/utils/latest'

const auth = useAuthStore()
const canExport = computed(() => auth.has('payroll:export'))
const isGroupScope = computed(() => auth.user?.dataScope === 'ALL')

const { companies, deptTree, loadCompanies, loadDepts } = useOrgOptions()

/** 查询防竞态：切维度/连点查询时，旧响应后到不得覆盖新结果。 */
const latest = createLatestOnly()

const currentYear = new Date().getFullYear()
const yearOptions = Array.from({ length: 6 }, (_, index) => String(currentYear - index))

const filters = ref<{ year: string; groupBy: ReportGroupBy; companyId: string; deptId: string }>({
  year: String(currentYear),
  groupBy: 'EMP',
  companyId: isGroupScope.value ? '' : auth.companyId || '',
  deptId: '',
})

const rows = ref<AnnualReportRow[]>([])
const loading = ref(false)
const exporting = ref(false)
const loadError = ref('')

const companyRequired = computed(() => filters.value.groupBy === 'DEPT' && isGroupScope.value)

async function query() {
  const invalid = validateAnnualFilters(filters.value, auth.user?.dataScope)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  const token = latest.next()
  loading.value = true
  loadError.value = ''
  try {
    const data = await reportApi.getAnnualReport({
      year: filters.value.year,
      groupBy: filters.value.groupBy,
      ...(filters.value.companyId ? { companyId: filters.value.companyId } : {}),
      ...(filters.value.deptId ? { deptId: filters.value.deptId } : {}),
    })
    if (!latest.isLatest(token)) return
    rows.value = data
  } catch (error) {
    if (!latest.isLatest(token)) return
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '年度汇总加载失败'
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
  const invalid = validateAnnualFilters(filters.value, auth.user?.dataScope)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  exporting.value = true
  try {
    const file = await reportApi.exportReport(
      {
        reportType: 'ANNUAL',
        params: buildAnnualParams(
          filters.value.year,
          filters.value.groupBy,
          filters.value.companyId,
          filters.value.deptId,
        ),
        format: 'XLSX',
      },
      `annual-${filters.value.year}-${filters.value.groupBy}.xlsx`,
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

watch(
  () => filters.value.groupBy,
  () => {
    // 列结构随维度变化：作废在途请求，切换后必须重新取数
    latest.invalidate()
    // 在途请求的 finally 因 isLatest 为假不会再复位 loading，这里必须手动复位，
    // 否则「切到 DEPT 且未选主体」时会一直转圈
    loading.value = false
    rows.value = []
    filters.value.deptId = ''
    if (validateAnnualFilters(filters.value, auth.user?.dataScope) === null) {
      void query()
    }
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
        <el-select v-model="filters.year" placeholder="年份" style="width: 110px">
          <el-option v-for="year in yearOptions" :key="year" :value="year" :label="`${year} 年`" />
        </el-select>
        <el-radio-group v-model="filters.groupBy">
          <el-radio-button value="EMP">按人</el-radio-button>
          <el-radio-button value="DEPT">按部门</el-radio-button>
        </el-radio-group>
        <el-select
          v-model="filters.companyId"
          clearable
          :placeholder="companyRequired ? '法人主体（必选）' : '法人主体（可选）'"
          style="width: 190px"
          :class="{ 'is-required': companyRequired }"
        >
          <el-option v-for="company in companies" :key="company.id" :value="company.id" :label="company.companyName" />
        </el-select>
        <el-tree-select
          v-model="filters.deptId"
          :data="deptTree"
          check-strictly
          clearable
          placeholder="部门（可选）"
          style="width: 170px"
          :render-after-expand="false"
        />
        <el-button type="primary" :icon="Search" @click="query">查询</el-button>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canExport" :icon="Download" :loading="exporting" @click="exportReport">导出 xlsx</el-button>
      </div>
    </div>

    <el-alert
      v-if="companyRequired && !filters.companyId"
      title="当前账号是集团数据范围：按部门汇总必须先选择法人主体，否则不同主体的同名部门会被合并加总"
      type="info"
      show-icon
      :closable="false"
      class="hint-alert"
    />
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="hint-alert" />

    <el-table
      v-loading="loading"
      :data="rows"
      border
      stripe
      show-summary
      :summary-method="summaryMethod"
      empty-text="该年度没有可统计的已锁定数据"
    >
      <el-table-column prop="year" label="年度" width="90" />
      <el-table-column v-if="filters.groupBy === 'DEPT'" label="部门" min-width="160">
        <template #default="{ row }">{{ row.deptName || row.deptId || '—' }}</template>
      </el-table-column>
      <template v-else>
        <el-table-column prop="empNo" label="工号" width="120" />
        <el-table-column prop="empName" label="姓名" width="120" />
      </template>
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
        <el-empty description="该年度没有可统计的已锁定数据" :image-size="90" />
      </template>
    </el-table>

    <div class="footnote muted">
      人数为去重后的在职人数（同一人跨月只计一次），年度值不等于各月相加。
    </div>
  </div>
</template>

<style scoped>
.hint-alert {
  margin-bottom: 10px;
}
.amount-cell {
  font-variant-numeric: tabular-nums;
}
.footnote {
  margin-top: 10px;
  font-size: 12px;
}
.is-required :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-warning) inset;
}
</style>
