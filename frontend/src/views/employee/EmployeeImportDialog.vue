<script setup lang="ts">
/**
 * 人员批量导入向导（弹窗版，入口在人员管理页）。
 *
 * - 模板 13 列固定、列名不可改；工号/证件号必填，日期 YYYY-MM-DD，基本工资两位小数，部门编码需已存在
 * - failRows > 0 时提交按钮禁用并说明原因（后端 409 + data.failRows 兜底）
 * - 提交会同时创建人员与薪酬档案，必须二次确认
 */
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { Download, UploadFilled } from '@element-plus/icons-vue'
import * as importApi from '@/api/import'
import { isApiError } from '@/api/client'
import { saveBlob } from '@/api/download'
import { useAuthStore } from '@/stores/auth'
import { useEmployeeImport } from '@/composables/useEmployeeImport'
import { batchSummary } from '@/utils/import'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  imported: []
}>()

const auth = useAuthStore()
const canExportErrors = computed(() => auth.has('payroll:export'))

const state = useEmployeeImport()
const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const selectedFile = ref<File | null>(null)
const fileList = ref<UploadFile[]>([])
const downloadingTemplate = ref(false)
const downloadingErrors = ref(false)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    state.reset()
    selectedFile.value = null
    fileList.value = []
  },
)

function onFileChange(uploadFile: UploadFile): void {
  if (!uploadFile.raw) return
  selectedFile.value = uploadFile.raw
  fileList.value = [uploadFile]
  state.reset()
}

function onFileRemove(): void {
  selectedFile.value = null
  fileList.value = []
}

async function downloadTemplate() {
  downloadingTemplate.value = true
  try {
    const file = await importApi.downloadEmployeeImportTemplate()
    saveBlob(file.blob, file.filename)
    ElMessage.success('模板已开始下载')
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '模板下载失败')
  } finally {
    downloadingTemplate.value = false
  }
}

async function validate() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择 xlsx 文件')
    return
  }
  const result = await state.validate(selectedFile.value)
  if (result) ElMessage.success(`预校验完成：${batchSummary(result)}`)
  else ElMessage.error(state.errorMessage.value)
}

async function downloadErrors() {
  if (!state.batch.value) return
  downloadingErrors.value = true
  try {
    const file = await importApi.downloadImportErrors(
      state.batch.value.batchId,
      `employee-import-errors-${state.batch.value.batchId}.xlsx`,
    )
    saveBlob(file.blob, file.filename)
    ElMessage.success('错误报告已开始下载')
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '错误报告下载失败')
  } finally {
    downloadingErrors.value = false
  }
}

async function commit() {
  if (!state.batch.value) return
  try {
    await ElMessageBox.confirm(
      `将导入 ${state.batch.value.successRows} 名员工及其薪酬档案，确认提交？`,
      '确认提交人员导入',
      { confirmButtonText: '提交', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const result = await state.commit()
  if (result) {
    ElMessage.success(`已导入 ${result.successRows} 名员工`)
    emit('imported')
    visible.value = false
  } else {
    ElMessage.error(state.errorMessage.value || '提交失败')
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="批量导入人员" width="720px" destroy-on-close>
    <el-alert v-if="state.errorMessage.value" :title="state.errorMessage.value" type="error" show-icon :closable="false" class="gap" />
    <el-alert
      v-if="!state.canSubmit.value"
      title="当前账号缺少 payroll:import:submit 权限，无法导入"
      type="warning"
      show-icon
      :closable="false"
      class="gap"
    />

    <div class="section">
      <div class="section-title">填表说明</div>
      <ul class="help-list">
        <li>模板共 <strong>13 列</strong>，列名与顺序不可修改。</li>
        <li><strong>工号</strong>与<strong>证件号</strong>必填；工号、证件号在系统内唯一。</li>
        <li>日期统一 <code>YYYY-MM-DD</code>（如 <code>2026-11-01</code>）。</li>
        <li>基本工资填数字并保留<strong>两位小数</strong>（如 <code>8600.00</code>）。</li>
        <li><strong>部门编码必须已存在</strong>，否则该行预校验失败。</li>
      </ul>
      <el-button type="primary" plain :icon="Download" :loading="downloadingTemplate" :disabled="!state.canSubmit.value" @click="downloadTemplate">
        下载人员模板 xlsx
      </el-button>
    </div>

    <div class="section">
      <div class="section-title">上传并预校验</div>
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
        <div class="el-upload__text">将人员 xlsx 拖到此处，或<em>点击选择文件</em></div>
      </el-upload>
      <el-button type="primary" :loading="state.validating.value" :disabled="!state.canSubmit.value" @click="validate">
        开始预校验
      </el-button>
    </div>

    <div v-if="state.batch.value" class="section">
      <div class="section-title">预校验结果</div>
      <div class="row">
        <el-tag type="success" size="small">成功 {{ state.batch.value.successRows }} 行</el-tag>
        <el-tag :type="state.batch.value.failRows > 0 ? 'danger' : 'info'" size="small">
          失败 {{ state.batch.value.failRows }} 行
        </el-tag>
        <span class="muted">{{ batchSummary(state.batch.value) }}</span>
        <el-button
          v-if="state.batch.value.failRows > 0 && canExportErrors"
          link
          type="primary"
          :icon="Download"
          :loading="downloadingErrors"
          @click="downloadErrors"
        >
          下载错误报告
        </el-button>
        <span v-else-if="state.batch.value.failRows > 0" class="muted">错误报告需要 payroll:export 权限</span>
      </div>

      <el-table v-if="state.batch.value.errors.length" :data="state.batch.value.errors" border size="small" max-height="220">
        <el-table-column prop="rowNo" label="行号" width="70" />
        <el-table-column prop="columnName" label="列" width="140" />
        <el-table-column prop="rawValue" label="原值" width="150" show-overflow-tooltip />
        <el-table-column prop="errorCode" label="错误码" width="140" />
        <el-table-column prop="errorMsg" label="原因" min-width="180" />
      </el-table>

      <div class="row submit-row">
        <el-alert
          v-if="state.blockedReason.value"
          :title="state.blockedReason.value"
          type="warning"
          show-icon
          :closable="false"
          class="blocked"
        />
        <el-alert
          v-else
          :title="`预校验无失败行，可导入 ${state.batch.value.successRows} 名员工及其薪酬档案`"
          type="success"
          show-icon
          :closable="false"
          class="blocked"
        />
        <el-button type="primary" :disabled="!state.commitEnabled.value" :loading="state.committing.value" @click="commit">
          确认提交
        </el-button>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.gap {
  margin-bottom: 12px;
}
.section {
  border-top: 1px solid #ebeef5;
  padding: 12px 0;
}
.section-title {
  font-weight: 600;
  margin-bottom: 8px;
}
.help-list {
  margin: 0 0 10px;
  padding-left: 18px;
  font-size: 12px;
  color: #606266;
  line-height: 20px;
}
.help-list code {
  color: #409eff;
}
.uploader {
  max-width: 480px;
  margin-bottom: 10px;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.submit-row {
  margin-top: 10px;
}
.blocked {
  flex: 1;
  min-width: 320px;
}
</style>
