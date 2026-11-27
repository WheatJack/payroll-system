<script setup lang="ts">
/**
 * 参保信息（人员抽屉内）。
 *
 * - 列表展示参保城市、缴费基数（空 = 按近 12 期平均应发自动计算）、公积金比例、生效区间
 * - 未配置时明确提示「社保公积金不会计入」，避免 HR 以为算错
 * - 新增时选择城市后展示该城市险种比例作为参考
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import * as insuranceApi from '@/api/insurance'
import { isApiError } from '@/api/client'
import type { InsuranceParam, SocialInsurance } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import MoneyInput from '@/components/MoneyInput.vue'
import { compare, coerce, format, formatOrZero } from '@/utils/money'
import { INSURANCE_CITIES, formatRateAsPercent, insuranceCityLabel, insuranceTypeLabel } from '@/utils/tax'

const props = defineProps<{ employeeId: string }>()
const auth = useAuthStore()
const canEdit = computed(() => auth.has('payroll:record:edit'))

const rows = ref<SocialInsurance[]>([])
const loading = ref(false)
const loadError = ref('')

const dialogVisible = ref(false)
const submitting = ref(false)
const formError = ref('')
const cityParams = ref<InsuranceParam[]>([])
const loadingParams = ref(false)

const form = reactive({
  cityCode: '',
  socialBase: '',
  housingFundRate: '',
  effectiveFrom: '',
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
    rows.value = await insuranceApi.listSocialInsurance(props.employeeId)
  } catch (error) {
    console.error('[payroll] 参保信息加载失败', error)
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '参保信息加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

async function loadCityParams(cityCode: string) {
  if (!cityCode) {
    cityParams.value = []
    return
  }
  loadingParams.value = true
  try {
    cityParams.value = await insuranceApi.listInsuranceParams(cityCode)
  } catch (error) {
    console.error('[payroll] 城市险种参数加载失败', error)
    cityParams.value = []
    ElMessage.warning('该城市的险种参数加载失败，比例参考暂不可用')
  } finally {
    loadingParams.value = false
  }
}

function openCreate() {
  form.cityCode = ''
  form.socialBase = ''
  form.housingFundRate = ''
  form.effectiveFrom = defaultEffectiveFrom()
  cityParams.value = []
  formError.value = ''
  dialogVisible.value = true
}

watch(
  () => form.cityCode,
  (cityCode) => void loadCityParams(cityCode),
)

function validateRate(value: string): string | null {
  const cleaned = coerce(value)
  if (cleaned === null) return '公积金比例需为合法数值（如 0.12）'
  if (compare(cleaned, '0') < 0 || compare(cleaned, '1') > 0) return '公积金比例应在 0 ~ 1 之间'
  return null
}

async function submit() {
  formError.value = ''
  if (!form.cityCode) {
    formError.value = '请选择参保城市'
    return
  }
  const base = form.socialBase.trim() === '' ? null : coerce(form.socialBase)
  const baseError = form.socialBase.trim() !== '' && base === null ? '缴费基数需为合法金额' : null
  if (baseError) {
    formError.value = baseError
    return
  }
  const rateError = form.housingFundRate.trim() === '' ? null : validateRate(form.housingFundRate)
  if (rateError) {
    formError.value = rateError
    return
  }
  if (!form.effectiveFrom) {
    formError.value = '请选择生效日期'
    return
  }

  submitting.value = true
  try {
    await insuranceApi.createSocialInsurance(props.employeeId, {
      cityCode: form.cityCode,
      ...(base !== null ? { socialBase: base } : {}),
      ...(form.housingFundRate.trim() !== '' ? { housingFundRate: coerce(form.housingFundRate) ?? undefined } : {}),
      effectiveFrom: form.effectiveFrom,
    })
    ElMessage.success('参保信息已保存')
    dialogVisible.value = false
    await load()
  } catch (error) {
    formError.value = isApiError(error) ? error.message : '保存失败，请稍后重试'
    ElMessage.error(formError.value)
  } finally {
    submitting.value = false
  }
}

watch(() => props.employeeId, load, { immediate: true })
defineExpose({ reload: load })
</script>

<template>
  <div v-loading="loading" class="insurance-panel">
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="gap" />
    <el-alert
      v-if="!loading && rows.length === 0"
      title="未配置参保信息，社保公积金不会计入；请先新增一段参保区间"
      type="warning"
      show-icon
      :closable="false"
      class="gap"
    />

    <div class="panel-toolbar">
      <el-button v-if="canEdit" type="primary" size="small" :icon="Plus" @click="openCreate">新增参保信息</el-button>
      <span class="muted">缴费基数留空时按近 12 期平均应发自动计算</span>
    </div>

    <el-table :data="rows" border size="small">
      <el-table-column label="参保城市" width="120">
        <template #default="{ row }">{{ insuranceCityLabel(row.cityCode) }}</template>
      </el-table-column>
      <el-table-column label="缴费基数" min-width="220">
        <template #default="{ row }">
          <span v-if="row.socialBase" class="amount-cell">{{ format(row.socialBase) }}</span>
          <span v-else class="muted">按近 12 期平均应发自动计算</span>
        </template>
      </el-table-column>
      <el-table-column label="公积金比例" width="120" align="right">
        <template #default="{ row }">{{ formatRateAsPercent(row.housingFundRate) }}</template>
      </el-table-column>
      <el-table-column label="生效区间" min-width="200">
        <template #default="{ row }">{{ row.effectiveFrom }} ~ {{ row.effectiveTo ?? '至今' }}</template>
      </el-table-column>
      <template #empty>
        <el-empty description="尚未配置参保信息" :image-size="70" />
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新增参保信息" width="620px" destroy-on-close>
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" class="gap" />
      <el-form label-width="110px">
        <el-form-item label="参保城市" required>
          <el-select v-model="form.cityCode" filterable allow-create default-first-option placeholder="选择或输入城市编码" style="width: 100%">
            <el-option v-for="city in INSURANCE_CITIES" :key="city.code" :value="city.code" :label="`${city.name}（${city.code}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="缴费基数">
          <MoneyInput v-model="form.socialBase" allow-empty placeholder="留空 = 按近 12 期平均应发自动计算" aria-label="缴费基数" />
          <div class="field-hint">留空表示按近 12 期平均应发自动计算</div>
        </el-form-item>
        <el-form-item label="公积金比例">
          <el-input v-model="form.housingFundRate" placeholder="如 0.12" aria-label="公积金比例" />
          <div class="field-hint">小数形式，0 ~ 1（如 0.12 = 12%）</div>
        </el-form-item>
        <el-form-item label="生效日期" required>
          <el-date-picker v-model="form.effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>

        <el-form-item v-if="form.cityCode" label="险种参考">
          <div v-loading="loadingParams" class="rate-preview">
            <el-table v-if="cityParams.length" :data="cityParams" size="small" border>
              <el-table-column label="险种" min-width="120">
                <template #default="{ row }">{{ insuranceTypeLabel(row.insuranceType) }}</template>
              </el-table-column>
              <el-table-column label="个人比例" width="110" align="right">
                <template #default="{ row }">{{ formatRateAsPercent(row.personalRate) }}</template>
              </el-table-column>
              <el-table-column label="基数下限" width="120" align="right">
                <template #default="{ row }">{{ formatOrZero(row.baseMin) }}</template>
              </el-table-column>
              <el-table-column label="基数上限" width="120" align="right">
                <template #default="{ row }">{{ formatOrZero(row.baseMax) }}</template>
              </el-table-column>
            </el-table>
            <span v-else-if="!loadingParams" class="muted">该城市暂无险种参数</span>
          </div>
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
.field-hint {
  font-size: 12px;
  color: #909399;
  line-height: 18px;
}
.rate-preview {
  width: 100%;
}
</style>
