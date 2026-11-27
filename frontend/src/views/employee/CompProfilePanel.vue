<script setup lang="ts">
/**
 * 薪酬档案面板：当前生效薪酬卡片 + 调薪历史时间线。
 *
 * 约定：
 * - 金额展示与编辑全部走字符串 + money 工具，出现任何 Number()/parseFloat 即为缺陷。
 * - 调薪默认生效日期为「次月 1 日」，可改；保存成功后重新拉取时间线，
 *   旧区间由后端截断并显示为已结束（前端不改写历史）。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as compProfileApi from '@/api/compProfile'
import { listPayItems } from '@/api/item'
import { isApiError } from '@/api/client'
import type { CompProfile, PayItem } from '@/api/models'
import MoneyInput from '@/components/MoneyInput.vue'
import { coerce, compare, formatOrZero, round } from '@/utils/money'

const props = defineProps<{
  employeeId: string
  employeeName?: string
}>()

const canEdit = ref(true)

const profiles = ref<CompProfile[]>([])
const loading = ref(false)
const loadError = ref('')

const payItems = ref<PayItem[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const formError = ref('')
const effectiveFrom = ref('')
const baseSalary = ref('0.00')
const changeReason = ref('')
const itemRows = ref<Array<{ itemCode: string; amount: string }>>([])

/** 当前生效：status=1 且未结束的最新区间。 */
const currentProfile = computed<CompProfile | null>(() => {
  const active = profiles.value.filter((profile) => profile.status === 1)
  const open = active.filter((profile) => profile.effectiveTo === null)
  const pool = open.length > 0 ? open : active
  if (pool.length === 0) return null
  return [...pool].sort((a, b) => (a.effectiveFrom < b.effectiveFrom ? 1 : -1))[0]
})

/** 历史按生效日期倒序展示。 */
const history = computed(() => [...profiles.value].sort((a, b) => (a.effectiveFrom < b.effectiveFrom ? 1 : -1)))

const itemNameMap = computed(() => {
  const map = new Map<string, string>()
  for (const item of payItems.value) map.set(item.itemCode, item.itemName)
  return map
})

function itemLabel(code: string): string {
  return itemNameMap.value.get(code) ?? code
}

async function load() {
  if (!props.employeeId) return
  loading.value = true
  loadError.value = ''
  try {
    profiles.value = await compProfileApi.listCompProfiles(props.employeeId)
  } catch (error) {
    profiles.value = []
    loadError.value = isApiError(error) ? error.message : '薪酬档案加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

async function loadPayItems() {
  try {
    payItems.value = await listPayItems({ category: 'FIXED', status: 1 })
  } catch {
    payItems.value = []
  }
}

/** 默认生效日期：次月 1 日（本地日历，不涉及时区换算的金额逻辑）。 */
function nextMonthFirstDay(): string {
  const now = new Date()
  const year = now.getMonth() === 11 ? now.getFullYear() + 1 : now.getFullYear()
  const month = now.getMonth() === 11 ? 1 : now.getMonth() + 2
  return `${year}-${String(month).padStart(2, '0')}-01`
}

function openAdjust() {
  const current = currentProfile.value
  effectiveFrom.value = nextMonthFirstDay()
  baseSalary.value = current ? round(coerce(current.baseSalary) ?? '0.00', 2) : '0.00'
  changeReason.value = ''
  itemRows.value = current
    ? current.items.map((item) => ({ itemCode: item.itemCode, amount: round(coerce(item.amount) ?? '0.00', 2) }))
    : []
  formError.value = ''
  dialogVisible.value = true
}

function addItemRow() {
  itemRows.value.push({ itemCode: '', amount: '0.00' })
}

function removeItemRow(index: number) {
  itemRows.value.splice(index, 1)
}

function validate(): string {
  if (!effectiveFrom.value) return '请选择生效日期'
  try {
    if (compare(baseSalary.value, '0') < 0) return '基本工资不能为负数'
  } catch {
    return '基本工资格式不正确'
  }
  const seen = new Set<string>()
  for (const row of itemRows.value) {
    if (!row.itemCode) return '存在未选择科目的津贴行'
    if (seen.has(row.itemCode)) return `科目 ${itemLabel(row.itemCode)} 重复`
    seen.add(row.itemCode)
    try {
      if (compare(row.amount, '0') < 0) return `津贴 ${itemLabel(row.itemCode)} 不能为负数`
    } catch {
      return `津贴 ${itemLabel(row.itemCode)} 金额格式不正确`
    }
  }
  return ''
}

async function submit() {
  formError.value = validate()
  if (formError.value) return

  const items = itemRows.value.map((row) => ({ itemCode: row.itemCode, amount: round(row.amount, 2) }))
  submitting.value = true
  try {
    const current = currentProfile.value
    if (current) {
      const updated = await compProfileApi.updateCompProfile(current.id, {
        effectiveFrom: effectiveFrom.value,
        baseSalary: round(baseSalary.value, 2),
        changeReason: changeReason.value || null,
        items,
      })
      ElMessage.success(`已保存新薪酬区间，自 ${updated.effectiveFrom} 生效`)
    } else {
      const created = await compProfileApi.createCompProfile({
        employeeId: props.employeeId,
        effectiveFrom: effectiveFrom.value,
        baseSalary: round(baseSalary.value, 2),
        changeReason: changeReason.value || null,
        items,
      })
      ElMessage.success(`薪酬档案已创建，自 ${created.effectiveFrom} 生效`)
    }
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
void loadPayItems()

defineExpose({ reload: load, canEdit })
</script>

<template>
  <div v-loading="loading" class="comp-profile-panel">
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="panel-error" />

    <el-card shadow="never" class="current-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">当前生效薪酬</span>
          <el-button type="primary" size="small" @click="openAdjust">
            {{ currentProfile ? '调薪' : '配置薪酬' }}
          </el-button>
        </div>
      </template>

      <template v-if="currentProfile">
        <div class="base-line">
          <span class="base-label">基本工资</span>
          <span class="base-amount">{{ formatOrZero(currentProfile.baseSalary) }}</span>
        </div>
        <div class="meta-line">
          <span>生效日期：{{ currentProfile.effectiveFrom }}</span>
          <span v-if="currentProfile.changeReason" class="muted">原因：{{ currentProfile.changeReason }}</span>
        </div>
        <div v-if="currentProfile.items.length" class="items-line">
          <el-tag v-for="item in currentProfile.items" :key="item.itemCode" size="small" type="info" class="item-tag">
            {{ itemLabel(item.itemCode) }} {{ formatOrZero(item.amount) }}
          </el-tag>
        </div>
        <div v-else class="muted">无固定津贴</div>
      </template>
      <el-empty v-else description="该员工尚未配置薪酬档案，无法参与工资生成" :image-size="80" />
    </el-card>

    <div class="timeline-block">
      <div class="block-title">调薪历史</div>
      <el-timeline v-if="profiles.length">
        <el-timeline-item
          v-for="profile in history"
          :key="profile.id"
          :timestamp="`${profile.effectiveFrom} ~ ${profile.effectiveTo ?? '至今'}`"
          placement="top"
          :type="profile.effectiveTo === null && profile.status === 1 ? 'primary' : 'info'"
        >
          <div class="timeline-item">
            <div class="timeline-head">
              <span class="timeline-amount">基本工资 {{ formatOrZero(profile.baseSalary) }}</span>
              <el-tag :type="profile.status === 1 ? 'success' : 'info'" size="small">
                {{ profile.status === 1 ? '有效' : '已作废' }}
              </el-tag>
              <el-tag v-if="profile.effectiveTo" size="small" type="warning">已结束</el-tag>
            </div>
            <div v-if="profile.items.length" class="timeline-items">
              <span v-for="item in profile.items" :key="item.itemCode" class="timeline-item-tag">
                {{ itemLabel(item.itemCode) }} {{ formatOrZero(item.amount) }}
              </span>
            </div>
            <div v-if="profile.changeReason" class="muted">原因：{{ profile.changeReason }}</div>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无调薪记录" :image-size="70" />
    </div>

    <el-dialog v-model="dialogVisible" :title="currentProfile ? '调薪' : '配置薪酬档案'" width="640px">
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" class="panel-error" />
      <el-form label-width="96px">
        <el-form-item label="生效日期" required>
          <el-date-picker v-model="effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          <div class="field-hint">默认为次月 1 日，可按公司调薪规则修改</div>
        </el-form-item>
        <el-form-item label="基本工资" required>
          <MoneyInput v-model="baseSalary" :scale="2" aria-label="基本工资" />
        </el-form-item>
        <el-form-item label="固定津贴">
          <div class="item-editor">
            <div v-for="(row, index) in itemRows" :key="index" class="item-row">
              <el-select v-model="row.itemCode" filterable placeholder="选择工资项" class="item-code">
                <el-option
                  v-for="item in payItems"
                  :key="item.itemCode"
                  :value="item.itemCode"
                  :label="`${item.itemName}（${item.itemCode}）`"
                />
                <el-option v-if="row.itemCode && !itemNameMap.has(row.itemCode)" :value="row.itemCode" :label="row.itemCode" />
              </el-select>
              <MoneyInput v-model="row.amount" :scale="2" class="item-amount" aria-label="津贴金额" />
              <el-button link type="danger" @click="removeItemRow(index)">删除</el-button>
            </div>
            <el-button link type="primary" @click="addItemRow">+ 添加津贴</el-button>
          </div>
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="changeReason" type="textarea" :rows="2" maxlength="255" show-word-limit />
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
.comp-profile-panel {
  min-height: 200px;
}
.panel-error {
  margin-bottom: 12px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-title {
  font-weight: 600;
}
.base-line {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.base-label {
  color: #909399;
  font-size: 13px;
}
.base-amount {
  font-size: 26px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #1f2d3d;
}
.meta-line {
  display: flex;
  gap: 18px;
  margin: 8px 0;
  font-size: 13px;
  color: #606266;
}
.items-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
}
.item-tag {
  font-variant-numeric: tabular-nums;
}
.timeline-block {
  margin-top: 20px;
}
.block-title {
  font-weight: 600;
  margin-bottom: 12px;
}
.timeline-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.timeline-amount {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}
.timeline-items {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 6px;
  font-size: 13px;
  color: #606266;
}
.timeline-item-tag {
  font-variant-numeric: tabular-nums;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  line-height: 18px;
  margin-top: 2px;
}
.item-editor {
  width: 100%;
}
.item-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.item-code {
  flex: 1.4;
}
.item-amount {
  flex: 1;
}
</style>
