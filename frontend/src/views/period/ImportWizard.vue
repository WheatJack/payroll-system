<script setup lang="ts">
/**
 * 导入向导（v1.1，M1.5）：下载模板 → 上传预校验 → 错误报告 → 确认提交。
 *
 * 关键点：
 * - `failRows > 0` 时提交按钮直接置灰并写明原因（后端仍会 409 兜底，双重保护）
 * - 同文件重复上传/重复提交由后端 409，前端给出人话提示
 * - 刷新后可按批次号回捞（GET /import/{batchId}）
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type UploadFile } from 'element-plus'
import { Back, Download, Refresh, UploadFilled } from '@element-plus/icons-vue'
import * as importApi from '@/api/import'
import { isApiError } from '@/api/client'
import { saveBlob } from '@/api/download'
import type { ImportBizType, ImportCommitResult, ImportMode } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import {
  IMPORT_POLL_INTERVAL_MS,
  IMPORT_POLL_MAX_ATTEMPTS,
  batchSummary,
  canCommit,
  commitBlockedReason,
  isReadyToCommit,
  isTerminalBatchStatus,
  pollExhausted,
  shouldKeepPolling,
  type ImportBatchView,
} from '@/utils/import'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const periodId = computed(() => String(route.params.id ?? ''))
const canSubmit = computed(() => auth.has('payroll:import:submit'))
const canExportErrors = computed(() => auth.has('payroll:export'))

const bizType = ref<ImportBizType>('FIXED')
const importMode = ref<ImportMode>('UPSERT')

const selectedFile = ref<File | null>(null)
const fileList = ref<UploadFile[]>([])

const batch = ref<ImportBatchView | null>(null)
const commitResult = ref<ImportCommitResult | null>(null)
const reopenBatchId = ref('')

const validating = ref(false)
const committing = ref(false)
const downloadingTemplate = ref(false)
const downloadingErrors = ref(false)
const errorMessage = ref('')

const blockedReason = computed(() => commitBlockedReason(batch.value, canSubmit.value))
const commitEnabled = computed(() => canCommit(batch.value, canSubmit.value) && !committing.value)
const ready = computed(() => isReadyToCommit(batch.value))
const validatingAsync = computed(() => batch.value?.status === 'VALIDATING')

/* ------------------------- 异步预校验轮询 ------------------------- */

const pollTimedOut = ref(false)
let pollTimer: number | undefined
let pollToken = 0
let pollAttempts = 0

function syncBatchQuery(batchId: string): void {
  void router.replace({ query: { ...route.query, batchId } })
}

function stopPolling(): void {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
  pollToken += 1
}

async function pollBatch(batchId: string, token: number): Promise<void> {
  if (token !== pollToken) return
  pollAttempts += 1
  try {
    const next = await importApi.getImportBatch(batchId)
    if (token !== pollToken) return
    batch.value = next
    if (isTerminalBatchStatus(next.status)) {
      stopPolling()
      if (next.status === 'VALIDATED') ElMessage.success(`预校验完成：${batchSummary(next)}`)
      else if (next.status === 'FAILED') ElMessage.error('预校验失败，请检查文件后重新上传')
      return
    }
  } catch (error) {
    if (token !== pollToken) return
    stopPolling()
    ElMessage.error(isApiError(error) ? error.message : '批次进度查询失败')
    return
  }
  if (pollExhausted(pollAttempts, IMPORT_POLL_MAX_ATTEMPTS)) {
    stopPolling()
    pollTimedOut.value = true
    return
  }
  pollTimer = window.setTimeout(() => void pollBatch(batchId, token), IMPORT_POLL_INTERVAL_MS)
}

function startPolling(batchId: string): void {
  stopPolling()
  pollAttempts = 0
  pollTimedOut.value = false
  const token = ++pollToken
  pollTimer = window.setTimeout(() => void pollBatch(batchId, token), IMPORT_POLL_INTERVAL_MS)
}

/** 超时后的手动刷新入口。 */
async function refreshBatch(): Promise<void> {
  if (!batch.value) return
  await reopen(batch.value.batchId)
}

function onFileChange(uploadFile: UploadFile): void {
  if (!uploadFile.raw) return
  stopPolling()
  pollTimedOut.value = false
  selectedFile.value = uploadFile.raw
  fileList.value = [uploadFile]
  batch.value = null
  commitResult.value = null
  errorMessage.value = ''
}

function onFileRemove(): void {
  selectedFile.value = null
  fileList.value = []
}

async function downloadTemplate() {
  if (!periodId.value) return
  downloadingTemplate.value = true
  try {
    const file = await importApi.downloadImportTemplate(periodId.value, bizType.value, `import-template-${bizType.value}.xlsx`)
    saveBlob(file.blob, file.filename)
    ElMessage.success('模板已开始下载')
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '模板下载失败')
  } finally {
    downloadingTemplate.value = false
  }
}

async function validate() {
  errorMessage.value = ''
  if (!selectedFile.value) {
    ElMessage.warning('请先选择 xlsx 文件')
    return
  }
  validating.value = true
  try {
    batch.value = await importApi.validateImport({
      file: selectedFile.value,
      periodId: periodId.value,
      bizType: bizType.value,
      importMode: importMode.value,
    })
    reopenBatchId.value = batch.value.batchId
    syncBatchQuery(batch.value.batchId)
    if (shouldKeepPolling(batch.value.status)) {
      ElMessage.info('预校验处理中，正在自动刷新进度…')
      startPolling(batch.value.batchId)
    } else {
      ElMessage.success(`预校验完成：${batchSummary(batch.value)}`)
    }
  } catch (error) {
    batch.value = null
    errorMessage.value = isApiError(error) ? error.message : '预校验失败，请稍后重试'
    ElMessage.error(errorMessage.value)
  } finally {
    validating.value = false
  }
}

async function reopen(id?: string) {
  const target = (id ?? reopenBatchId.value).trim()
  if (!target) {
    ElMessage.warning('请输入批次号')
    return
  }
  // 先停掉上一个批次的轮询，否则旧 token 的 tick 会把 batch 覆盖回旧批次
  stopPolling()
  try {
    batch.value = await importApi.getImportBatch(target)
    reopenBatchId.value = batch.value.batchId
    syncBatchQuery(batch.value.batchId)
    commitResult.value = null
    pollTimedOut.value = false
    if (shouldKeepPolling(batch.value.status)) {
      startPolling(batch.value.batchId)
      ElMessage.info('批次仍在预校验中，正在自动刷新进度…')
    } else {
      ElMessage.success(`已载入批次 ${batch.value.batchId}（${batch.value.status}）`)
    }
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '批次查询失败')
  }
}

async function downloadErrors() {
  if (!batch.value) return
  downloadingErrors.value = true
  try {
    const file = await importApi.downloadImportErrors(batch.value.batchId, `import-errors-${batch.value.batchId}.xlsx`)
    saveBlob(file.blob, file.filename)
    ElMessage.success('错误报告已开始下载')
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '错误报告下载失败')
  } finally {
    downloadingErrors.value = false
  }
}

async function commit() {
  if (!batch.value) return
  const reason = commitBlockedReason(batch.value, canSubmit.value)
  if (reason) {
    ElMessage.warning(reason)
    return
  }
  committing.value = true
  errorMessage.value = ''
  try {
    commitResult.value = await importApi.commitImport(batch.value.batchId)
    batch.value = { ...batch.value, status: 'COMMITTED' }
    ElMessage.success(`提交成功：${commitResult.value.successRows} 行`)
  } catch (error) {
    if (isApiError(error) && error.code === 409) {
      const data = error.data as { batchId?: string; failRows?: number } | null
      if (data && typeof data.failRows === 'number' && data.failRows > 0) {
        ElMessage.error(`预校验有 ${data.failRows} 行失败，禁止提交；请修正后重新上传`)
        await reopen(data.batchId ?? batch.value.batchId)
      } else {
        ElMessage.error(error.message || '该批次已提交或存在冲突，请重新上传')
      }
    } else {
      errorMessage.value = isApiError(error) ? error.message : '提交失败，请稍后重试'
      ElMessage.error(errorMessage.value)
    }
  } finally {
    committing.value = false
  }
}

function backToGrid() {
  void router.push({ name: 'payroll-grid', params: { id: periodId.value } })
}

onMounted(() => {
  // 批次号写进 URL：刷新/分享链接后可继续
  const fromQuery = typeof route.query.batchId === 'string' ? route.query.batchId : ''
  if (fromQuery) {
    reopenBatchId.value = fromQuery
    void reopen(fromQuery)
  }
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-button :icon="Back" @click="backToGrid">返回编制台</el-button>
        <span class="import-title">导入工资数据</span>
        <el-tag v-if="batch" size="small" type="info">批次 {{ batch.batchId }} · {{ batch.status }}</el-tag>
      </div>
      <div class="toolbar-actions">
        <el-input v-model="reopenBatchId" placeholder="批次号" clearable style="width: 140px" />
        <el-button :icon="Refresh" @click="reopen()">按批次号打开</el-button>
      </div>
    </div>

    <el-alert
      v-if="!canSubmit"
      title="当前账号没有 payroll:import:submit 权限，只能查看"
      type="warning"
      show-icon
      :closable="false"
      class="gap"
    />
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" class="gap" />

    <el-steps :active="commitResult ? 3 : ready ? 2 : batch ? 1 : 0" finish-status="success" class="gap">
      <el-step title="下载模板" />
      <el-step title="上传并预校验" />
      <el-step title="确认提交" />
    </el-steps>

    <div class="import-section">
      <div class="section-title">1. 下载导入模板</div>
      <div class="section-body">
        <el-radio-group v-model="bizType">
          <el-radio-button value="FIXED">固定项</el-radio-button>
          <el-radio-button value="VARIABLE">变动项</el-radio-button>
        </el-radio-group>
        <el-button type="primary" plain :icon="Download" :loading="downloadingTemplate" @click="downloadTemplate">
          下载模板 xlsx
        </el-button>
        <span class="muted">列头由工资项字典动态生成；单文件 ≤ 10MB、≤ 5 万行</span>
      </div>
    </div>

    <div class="import-section">
      <div class="section-title">2. 上传并预校验</div>
      <div class="section-body column">
        <el-upload
          drag
          :auto-upload="false"
          :limit="1"
          accept=".xlsx,.xls"
          :file-list="fileList"
          :on-change="onFileChange"
          :on-remove="onFileRemove"
          class="uploader"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">将 xlsx 拖到此处，或<em>点击选择文件</em></div>
        </el-upload>

        <div class="row">
          <span class="label">导入模式</span>
          <el-radio-group v-model="importMode">
            <el-radio-button value="UPSERT">有则覆盖</el-radio-button>
            <el-radio-button value="APPEND">有则累加</el-radio-button>
            <el-radio-button value="INSERT_ONLY">有则跳过</el-radio-button>
          </el-radio-group>
          <el-button type="primary" :loading="validating" :disabled="!canSubmit" @click="validate">开始预校验</el-button>
        </div>
      </div>
    </div>

    <div v-if="batch" class="import-section">
      <div class="section-title">3. 预校验结果</div>
      <div class="section-body column">
        <div class="row">
          <el-tag v-if="validatingAsync" type="warning" size="small">预校验中…</el-tag>
          <el-tag type="success" size="small">成功 {{ batch.successRows }} 行</el-tag>
          <el-tag :type="batch.failRows > 0 ? 'danger' : 'info'" size="small">失败 {{ batch.failRows }} 行</el-tag>
          <span class="muted">{{ batchSummary(batch) }}</span>
          <el-button
            v-if="batch.failRows > 0 && canExportErrors"
            link
            type="primary"
            :icon="Download"
            :loading="downloadingErrors"
            @click="downloadErrors"
          >
            下载错误报告
          </el-button>
          <span v-else-if="batch.failRows > 0" class="muted">错误报告需要 payroll:export 权限</span>
        </div>

        <el-alert
          v-if="pollTimedOut"
          title="轮询超时，批次可能仍在处理中"
          type="warning"
          show-icon
          :closable="false"
          class="poll-alert"
        >
          <el-button link type="primary" @click="refreshBatch">手动刷新批次状态</el-button>
        </el-alert>

        <el-table v-if="batch.errors.length" :data="batch.errors" border size="small" max-height="260">
          <el-table-column prop="rowNo" label="行号" width="80" />
          <el-table-column prop="columnName" label="列" width="140" />
          <el-table-column prop="rawValue" label="原值" width="160" show-overflow-tooltip />
          <el-table-column prop="errorCode" label="错误码" width="140" />
          <el-table-column prop="errorMsg" label="原因" min-width="200" />
        </el-table>

        <div class="row">
          <el-alert
            v-if="blockedReason"
            :title="blockedReason"
            type="warning"
            show-icon
            :closable="false"
            class="blocked-alert"
          />
          <el-alert
            v-else
            title="预校验无失败行，可以提交；提交会写入核算明细并重算合计"
            type="success"
            show-icon
            :closable="false"
            class="blocked-alert"
          />
          <el-button type="primary" :disabled="!commitEnabled" :loading="committing" @click="commit">
            确认提交
          </el-button>
        </div>

        <el-alert
          v-if="commitResult"
          :title="`已提交：${commitResult.successRows} 行成功，${commitResult.failRows} 行失败（状态 ${commitResult.status}）`"
          type="success"
          show-icon
          :closable="false"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.gap {
  margin-bottom: 14px;
}
.import-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
}
.import-section {
  border-top: 1px solid #ebeef5;
  padding: 14px 0;
}
.section-title {
  font-weight: 600;
  margin-bottom: 10px;
}
.section-body {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.section-body.column {
  flex-direction: column;
  align-items: stretch;
}
.row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.label {
  font-size: 13px;
  color: #606266;
}
.uploader {
  max-width: 520px;
}
.blocked-alert {
  flex: 1;
  min-width: 320px;
}
.poll-alert {
  margin: 8px 0;
}
</style>
