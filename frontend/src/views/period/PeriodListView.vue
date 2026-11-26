<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import * as periodApi from '@/api/period'
import { isApiError } from '@/api/client'
import type { Period, PeriodStatus, RunProgress } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import { format } from '@/utils/money'

const auth = useAuthStore()
const router = useRouter()

const canEdit = computed(() => auth.has('payroll:record:edit'))
const canLock = computed(() => auth.has('payroll:period:lock'))
const canUnlock = computed(() => auth.has('payroll:period:unlock'))

const STATUS_META: Record<PeriodStatus, { label: string; type: 'info' | 'primary' | 'success' | 'warning' | 'danger' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  EDITING: { label: '编制中', type: 'primary' },
  LOCKED: { label: '已锁定', type: 'success' },
  ARCHIVED: { label: '已归档', type: 'warning' },
}

const rows = ref<Period[]>([])
const loading = ref(false)
const loadError = ref('')

/* ---------- 新建期间 ---------- */
const createVisible = ref(false)
const periodYm = ref('')
const creating = ref(false)
const createError = ref('')

/* ---------- 生成进度（轮询 /runs/{runId}） ---------- */
const runVisible = ref(false)
const runId = ref('')
const runProgress = ref<RunProgress | null>(null)
const runError = ref('')
let pollTimer: number | null = null
let pollAttempts = 0

/* ---------- 解锁 ---------- */
const unlockVisible = ref(false)
const unlockTarget = ref<Period | null>(null)
const unlockReason = ref('')
const unlocking = ref(false)
const unlockError = ref('')

const runPercent = computed(() => {
  const progress = runProgress.value
  if (!progress || progress.total <= 0) return 0
  return Math.min(100, Math.round(((progress.success + progress.failed) / progress.total) * 100))
})

const skippedEntries = computed(() => Object.entries(runProgress.value?.skipped ?? {}))

function statusMeta(status: PeriodStatus) {
  return STATUS_META[status]
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await periodApi.listPeriods(auth.companyId || undefined)
  } catch (error) {
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '期间列表加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  periodYm.value = ''
  createError.value = ''
  createVisible.value = true
}

async function submitCreate() {
  createError.value = ''
  if (!periodYm.value) {
    createError.value = '请选择工资年月'
    return
  }
  creating.value = true
  try {
    // 契约按 (companyId, periodYm) 幂等，重复创建返回已有期间
    const period = await periodApi.createPeriod({ companyId: auth.companyId || null, periodYm: periodYm.value })
    ElMessage.success(`期间 ${period.periodYm} 已就绪（${STATUS_META[period.status].label}）`)
    createVisible.value = false
    await load()
  } catch (error) {
    createError.value = isApiError(error) ? error.message : '新建期间失败'
    ElMessage.error(createError.value)
  } finally {
    creating.value = false
  }
}

/* ---------- 生成 + 轮询 ---------- */

function stopPolling() {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function pollRun() {
  if (!runId.value) return
  pollAttempts += 1
  try {
    const progress = await periodApi.getRun(runId.value)
    runProgress.value = progress
    if (progress.status === 'SUCCESS' || progress.status === 'FAILED') {
      stopPolling()
      if (progress.status === 'SUCCESS') {
        ElMessage.success(`生成完成：成功 ${progress.success} 人${progress.failed ? `，失败 ${progress.failed} 人` : ''}`)
      } else {
        ElMessage.error(`生成失败：${progress.failed} 人`)
      }
      await load()
      return
    }
  } catch (error) {
    runError.value = isApiError(error) ? error.message : '批次进度查询失败'
    stopPolling()
    return
  }
  if (pollAttempts >= 300) {
    runError.value = '轮询超时（5 分钟），请刷新期间列表确认结果'
    stopPolling()
    return
  }
  pollTimer = window.setTimeout(pollRun, 1000)
}

async function generate(period: Period) {
  runError.value = ''
  runProgress.value = null
  pollAttempts = 0
  try {
    const result = await periodApi.generatePeriod(period.id)
    runId.value = result.runId
    runVisible.value = true
    await pollRun()
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '生成失败')
  }
}

async function lock(period: Period) {
  try {
    await ElMessageBox.confirm(
      `锁定后 ${period.periodYm} 将禁止编辑，且锁定人不能是本期编制人。确认锁定？`,
      '锁定期间',
      { confirmButtonText: '锁定', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await periodApi.lockPeriod(period.id)
    ElMessage.success('期间已锁定')
    await load()
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '锁定失败')
  }
}

function openUnlock(period: Period) {
  unlockTarget.value = period
  unlockReason.value = ''
  unlockError.value = ''
  unlockVisible.value = true
}

async function submitUnlock() {
  if (!unlockTarget.value) return
  unlockError.value = ''
  if (!unlockReason.value.trim()) {
    unlockError.value = '请填写解锁原因（写入审计）'
    return
  }
  unlocking.value = true
  try {
    await periodApi.unlockPeriod(unlockTarget.value.id, unlockReason.value.trim())
    ElMessage.success('期间已解锁，回到编制中')
    unlockVisible.value = false
    await load()
  } catch (error) {
    unlockError.value = isApiError(error) ? error.message : '解锁失败'
    ElMessage.error(unlockError.value)
  } finally {
    unlocking.value = false
  }
}

function openGrid(period: Period) {
  void router.push({ name: 'payroll-grid', params: { id: period.id } })
}

function canGenerate(period: Period): boolean {
  return canEdit.value && (period.status === 'DRAFT' || period.status === 'EDITING')
}

onBeforeUnmount(stopPolling)
onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <span class="muted">锁定人不能是本期编制人（内控 C4）；解锁必须填写原因并写审计</span>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openCreate">新建期间</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="list-error" />

    <el-table v-loading="loading" :data="rows" border stripe size="default">
      <el-table-column prop="periodYm" label="工资年月" width="120" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusMeta(row.status).type" size="small">
            {{ statusMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="employeeCount" label="人数" width="90" />
      <el-table-column label="应发合计" width="150" align="right">
        <template #default="{ row }">
          <span class="amount-cell">{{ format(row.totalGross) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="实发合计" width="150" align="right">
        <template #default="{ row }">
          <span class="amount-cell">{{ format(row.totalNet) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="hpd" label="计薪天数" width="100" align="right" />
      <el-table-column label="操作" min-width="320" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openGrid(row)">编制台</el-button>
          <el-button v-if="canGenerate(row)" link type="primary" @click="generate(row)">生成</el-button>
          <el-button v-if="canLock && row.status === 'EDITING'" link type="warning" @click="lock(row)">锁定</el-button>
          <el-button v-if="canUnlock && row.status === 'LOCKED'" link type="danger" @click="openUnlock(row)">
            解锁
          </el-button>
          <span v-if="row.status === 'ARCHIVED'" class="muted">归档只读</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无工资期间，请先新建期间" :image-size="90" />
      </template>
    </el-table>

    <!-- 新建期间 -->
    <el-dialog v-model="createVisible" title="新建工资期间" width="420px" destroy-on-close>
      <el-alert v-if="createError" :title="createError" type="error" show-icon :closable="false" class="form-error" />
      <el-form label-width="90px">
        <el-form-item label="工资年月" required>
          <el-date-picker v-model="periodYm" type="month" value-format="YYYY-MM" placeholder="选择年月" style="width: 100%" />
        </el-form-item>
        <div class="muted">同一主体同一年月只会存在一条期间，重复创建会返回已有期间。</div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 生成进度 -->
    <el-dialog v-model="runVisible" title="生成基础数据" width="620px" :close-on-click-modal="false">
      <el-alert v-if="runError" :title="runError" type="error" show-icon :closable="false" class="form-error" />
      <div class="run-status">
        <span>批次状态：</span>
        <el-tag
          :type="runProgress?.status === 'SUCCESS' ? 'success' : runProgress?.status === 'FAILED' ? 'danger' : 'primary'"
          size="small"
        >
          {{ runProgress?.status ?? 'RUNNING' }}
        </el-tag>
        <span class="muted run-id">runId: {{ runId }}</span>
      </div>
      <el-progress :percentage="runPercent" :status="runProgress?.status === 'FAILED' ? 'exception' : undefined" />
      <div class="run-counts">
        <span>应生成 {{ runProgress?.total ?? 0 }} 人</span>
        <span class="ok">成功 {{ runProgress?.success ?? 0 }}</span>
        <span class="fail">失败 {{ runProgress?.failed ?? 0 }}</span>
      </div>

      <div v-if="runProgress?.errors?.length" class="run-block">
        <div class="run-block-title">失败明细</div>
        <el-table :data="runProgress.errors" size="small" border max-height="180">
          <el-table-column prop="empNo" label="工号" width="110" />
          <el-table-column prop="message" label="原因" />
        </el-table>
      </div>

      <div v-if="skippedEntries.length" class="run-block">
        <div class="run-block-title">跳过人员（不产生 0 元记录）</div>
        <el-table :data="skippedEntries.map(([empNo, reason]) => ({ empNo, reason }))" size="small" border max-height="180">
          <el-table-column prop="empNo" label="工号" width="110" />
          <el-table-column prop="reason" label="跳过原因" />
        </el-table>
      </div>

      <template #footer>
        <el-button @click="runVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 解锁 -->
    <el-dialog v-model="unlockVisible" title="解锁期间（高危）" width="480px" destroy-on-close>
      <el-alert v-if="unlockError" :title="unlockError" type="error" show-icon :closable="false" class="form-error" />
      <el-alert
        :title="`解锁 ${unlockTarget?.periodYm ?? ''} 后期间回到「编制中」，操作全程审计。`"
        type="warning"
        show-icon
        :closable="false"
        class="form-error"
      />
      <el-form label-width="90px">
        <el-form-item label="解锁原因" required>
          <el-input v-model="unlockReason" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="unlockVisible = false">取消</el-button>
        <el-button type="danger" :loading="unlocking" @click="submitUnlock">确认解锁</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.list-error,
.form-error {
  margin-bottom: 12px;
}
.amount-cell {
  font-variant-numeric: tabular-nums;
}
.run-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.run-id {
  margin-left: auto;
  font-size: 12px;
}
.run-counts {
  display: flex;
  gap: 20px;
  margin: 10px 0 4px;
  font-size: 13px;
  color: #606266;
}
.run-counts .ok {
  color: #67c23a;
}
.run-counts .fail {
  color: #f56c6c;
}
.run-block {
  margin-top: 16px;
}
.run-block-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
}
</style>
