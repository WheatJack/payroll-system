<script setup lang="ts">
/**
 * 年度个税核对（仅统计已锁定/已归档期间）。
 *
 * 口径：税负率 = 累计已预扣 ÷ 累计收入，**不可加总**（合计行显示 —）；
 * 金额全部用 money 计算与展示，右对齐。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Search } from '@element-plus/icons-vue'
import * as taxApi from '@/api/tax'
import * as reportApi from '@/api/report'
import * as employeeApi from '@/api/employee'
import { isApiError } from '@/api/client'
import { saveBlob } from '@/api/download'
import type { AnnualTaxRow } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import { useOrgOptions } from '@/composables/useOrgOptions'
import { formatOrZero, sum } from '@/utils/money'
import { paginateRows } from '@/utils/report'
import { formatRateAsPercent } from '@/utils/tax'

const auth = useAuthStore()
const canExport = computed(() => auth.has('payroll:export'))
const { deptTree, loadDepts } = useOrgOptions()

const currentYear = new Date().getFullYear()
const yearOptions = Array.from({ length: 6 }, (_, index) => String(currentYear - index))

const filters = ref({
  year: String(currentYear - 1),
  deptId: '',
  employeeId: '',
})

const rows = ref<AnnualTaxRow[]>([])
const excludedPeriods = ref<string[]>([])
const loading = ref(false)
const loadError = ref('')

const currentPage = ref(1)
const pageSize = ref(50)
const pagedRows = computed(() => paginateRows(rows.value, currentPage.value, pageSize.value))

const employeeOptions = ref<Array<{ id: string; label: string }>>([])
const employeeLoading = ref(false)

const excludedText = computed(() => {
  const list = excludedPeriods.value.filter((item) => item.trim() !== '')
  return list.length ? `${list.join('、')} 已有工资数据但未锁定，未计入本次统计` : null
})

const description = '用于核对个税累计预扣是否正确；税负率 = 累计已预扣 ÷ 累计收入'

async function searchEmployees(keyword: string) {
  const value = keyword.trim()
  if (!value) {
    employeeOptions.value = []
    return
  }
  employeeLoading.value = true
  try {
    const page = await employeeApi.listEmployees({ current: 1, size: 20, keyword: value })
    employeeOptions.value = page.records.map((employee) => ({
      id: employee.id,
      label: `${employee.empNo} ${employee.name}`,
    }))
  } catch (error) {
    console.error('[payroll] 员工搜索失败', error)
    employeeOptions.value = []
  } finally {
    employeeLoading.value = false
  }
}

async function query() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await taxApi.getAnnualTaxReport({
      year: filters.value.year,
      ...(filters.value.employeeId ? { employeeId: filters.value.employeeId } : {}),
      ...(filters.value.deptId ? { deptId: filters.value.deptId } : {}),
    })
    // 契约：{ rows, excludedPeriods }。若部署的后端还是旧版数组，明确报错而不是静默当空数据
    if (!result || !Array.isArray(result.rows)) {
      rows.value = []
      excludedPeriods.value = []
      loadError.value = '服务端返回结构不是 { rows, excludedPeriods }（可能后端版本过旧），请升级后端后重试'
      ElMessage.error(loadError.value)
      return
    }
    rows.value = result.rows
    excludedPeriods.value = result.excludedPeriods ?? []
    currentPage.value = 1
  } catch (error) {
    rows.value = []
    excludedPeriods.value = []
    loadError.value = isApiError(error) ? error.message : '年度个税核对加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

const totals = computed(() => ({
  paidMonths: rows.value.reduce((total, row) => total + (row.paidMonths ?? 0), 0),
  cumIncome: sum(rows.value.map((row) => row.cumIncome), '0.00'),
  cumBasicDeduction: sum(rows.value.map((row) => row.cumBasicDeduction), '0.00'),
  cumSocial: sum(rows.value.map((row) => row.cumSocial), '0.00'),
  cumFund: sum(rows.value.map((row) => row.cumFund), '0.00'),
  cumSpecial: sum(rows.value.map((row) => row.cumSpecial), '0.00'),
  taxable: sum(rows.value.map((row) => row.taxable), '0.00'),
  cumTax: sum(rows.value.map((row) => row.cumTax), '0.00'),
  lastTax: sum(rows.value.map((row) => row.lastTax), '0.00'),
}))

/** 合计行：金额逐列加总；税负率不可加总，显示 —。 */
function summaryMethod({ columns }: { columns: Array<{ property?: string }> }): string[] {
  return columns.map((column, index) => {
    if (index === 0) return '合计'
    switch (column.property) {
      case 'paidMonths':
        return String(totals.value.paidMonths)
      case 'cumIncome':
        return formatOrZero(totals.value.cumIncome)
      case 'cumBasicDeduction':
        return formatOrZero(totals.value.cumBasicDeduction)
      case 'cumSocial':
        return formatOrZero(totals.value.cumSocial)
      case 'cumFund':
        return formatOrZero(totals.value.cumFund)
      case 'cumSpecial':
        return formatOrZero(totals.value.cumSpecial)
      case 'taxable':
        return formatOrZero(totals.value.taxable)
      case 'cumTax':
        return formatOrZero(totals.value.cumTax)
      case 'lastTax':
        return formatOrZero(totals.value.lastTax)
      case 'effectiveRate':
        return '—'
      default:
        return ''
    }
  })
}

const exporting = ref(false)

/** 服务端 xlsx 导出（ANNUAL_TAX 已进契约，金额/月数/税负率格式由后端处理）。 */
async function exportReport() {
  if (rows.value.length === 0) {
    ElMessage.warning('没有可导出的数据')
    return
  }
  exporting.value = true
  try {
    const file = await reportApi.exportReport(
      {
        reportType: 'ANNUAL_TAX',
        params: {
          year: filters.value.year,
          ...(filters.value.deptId ? { deptId: filters.value.deptId } : {}),
          ...(filters.value.employeeId ? { employeeId: filters.value.employeeId } : {}),
        },
        format: 'XLSX',
      },
      `annual-tax-${filters.value.year}.xlsx`,
    )
    saveBlob(file.blob, file.filename)
    ElMessage.success('导出已开始下载')
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  void loadDepts(auth.companyId)
  void query()
})
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-select v-model="filters.year" placeholder="年度" style="width: 110px">
          <el-option v-for="year in yearOptions" :key="year" :value="year" :label="`${year} 年`" />
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
        <el-select
          v-model="filters.employeeId"
          filterable
          remote
          clearable
          reserve-keyword
          :remote-method="searchEmployees"
          :loading="employeeLoading"
          placeholder="员工（工号/姓名，可选）"
          style="width: 190px"
        >
          <el-option v-for="employee in employeeOptions" :key="employee.id" :value="employee.id" :label="employee.label" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="query">查询</el-button>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canExport" :icon="Download" :disabled="rows.length === 0" :loading="exporting" @click="exportReport">
          导出 xlsx
        </el-button>
      </div>
    </div>

    <el-alert :title="description" type="info" show-icon :closable="false" class="hint-alert" />
    <el-alert v-if="excludedText" :title="excludedText" type="warning" show-icon :closable="false" class="hint-alert" />
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="hint-alert" />
    <el-alert
      v-if="!loading && !loadError && rows.length === 0"
      title="仅统计已锁定（LOCKED）与已归档期间；所选年度没有已锁定数据时为空属正常"
      type="info"
      show-icon
      :closable="false"
      class="hint-alert"
    />

    <el-table
      v-loading="loading"
      :data="pagedRows"
      border
      stripe
      show-summary
      :summary-method="summaryMethod"
      empty-text="该年度没有可核对的已锁定数据"
    >
      <el-table-column prop="empNo" label="工号" width="110" fixed="left" />
      <el-table-column prop="empName" label="姓名" width="100" fixed="left" />
      <el-table-column prop="deptName" label="部门" min-width="130" show-overflow-tooltip />
      <el-table-column prop="paidMonths" label="计薪月数" width="90" align="right" />
      <el-table-column prop="cumIncome" label="累计收入" width="130" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.cumIncome) }}</span></template>
      </el-table-column>
      <el-table-column prop="cumBasicDeduction" label="累计减除" width="130" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.cumBasicDeduction) }}</span></template>
      </el-table-column>
      <el-table-column prop="cumSocial" label="累计社保" width="130" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.cumSocial) }}</span></template>
      </el-table-column>
      <el-table-column prop="cumFund" label="累计公积金" width="130" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.cumFund) }}</span></template>
      </el-table-column>
      <el-table-column prop="cumSpecial" label="累计专项" width="130" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.cumSpecial) }}</span></template>
      </el-table-column>
      <el-table-column prop="taxable" label="应纳税所得额" width="140" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.taxable) }}</span></template>
      </el-table-column>
      <el-table-column prop="cumTax" label="累计已预扣" width="130" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.cumTax) }}</span></template>
      </el-table-column>
      <el-table-column prop="lastTax" label="最近一期个税" width="130" align="right">
        <template #default="{ row }"><span class="amount-cell">{{ formatOrZero(row.lastTax) }}</span></template>
      </el-table-column>
      <el-table-column prop="effectiveRate" label="税负率" width="110" align="right">
        <template #default="{ row }">{{ formatRateAsPercent(row.effectiveRate) }}</template>
      </el-table-column>
      <template #empty>
        <el-empty description="该年度没有可核对的已锁定数据" :image-size="90" />
      </template>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="rows.length"
        :page-sizes="[50, 100, 200]"
        layout="total, sizes, prev, pager, next"
        @size-change="currentPage = 1"
      />
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
.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
