<script setup lang="ts">
/**
 * 系统参数（sys:config:view / sys:config:edit）。
 *
 * - 计薪天数、工龄工资标准、法定节假日等从后端 sys_config 读取，不再硬编码
 * - `editable=false` 的行只读（按钮禁用、置灰）
 * - 数值类（DECIMAL/INT）按字符串校验并走 money.coerce，不用 Number()/parseFloat
 * - 保存成功提示「已保存，下次生成工资时生效」
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as configApi from '@/api/config'
import type { ConfigValueType, SystemConfig } from '@/api/config'
import { isApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { configInputPlaceholder, normalizeConfigValue } from '@/utils/config'

const auth = useAuthStore()
const canEdit = computed(() => auth.has('sys:config:edit'))

const rows = ref<SystemConfig[]>([])
const loading = ref(false)
const loadError = ref('')

const hasWarning = computed(() => rows.value.some((row) => !!row.warning))
const dialogVisible = ref(false)
const editing = ref<SystemConfig | null>(null)
const draftValue = ref('')
const inputError = ref('')
const submitting = ref(false)

const TYPE_LABELS: Record<ConfigValueType, string> = {
  DECIMAL: '数值（小数）',
  INT: '整数',
  DATE_LIST: '日期列表',
  STRING: '文本',
}

function typeLabel(type: ConfigValueType): string {
  return TYPE_LABELS[type] ?? type
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await configApi.listConfigs()
  } catch (error) {
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '系统参数加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function openEdit(row: SystemConfig) {
  if (!row.editable) return
  editing.value = row
  draftValue.value = row.value
  inputError.value = ''
  dialogVisible.value = true
}

async function submit() {
  if (!editing.value) return
  inputError.value = ''
  const result = normalizeConfigValue(editing.value.valueType, draftValue.value)
  if (!result.ok) {
    inputError.value = result.error ?? '参数值不合法'
    return
  }
  submitting.value = true
  try {
    await configApi.updateConfig(editing.value.key, result.value)
    ElMessage.success('已保存，下次生成工资时生效')
    dialogVisible.value = false
    await load()
  } catch (error) {
    inputError.value = isApiError(error) ? error.message : '保存失败，请稍后重试'
    ElMessage.error(inputError.value)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <span class="muted">参数改动在下次生成工资时生效；只读参数由系统维护</span>
      </div>
      <div class="toolbar-actions">
        <el-tag v-if="!canEdit" type="info" size="small">当前账号无 sys:config:edit，参数只读</el-tag>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="list-error" />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column label="参数" min-width="220">
        <template #default="{ row }">
          <div class="config-name">{{ row.description }}</div>
          <div class="config-key muted">{{ row.key }}</div>
        </template>
      </el-table-column>
      <el-table-column label="当前值" min-width="180">
        <template #default="{ row }">
          <span class="config-value" :class="{ 'is-readonly': !row.editable }">{{ row.value || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="hasWarning" label="警示" min-width="200">
        <template #default="{ row }">
          <span v-if="row.warning" class="config-warning">{{ row.warning }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ typeLabel(row.valueType) }}</template>
      </el-table-column>
      <el-table-column label="可编辑" width="100">
        <template #default="{ row }">
          <el-tag :type="row.editable ? 'success' : 'info'" size="small">{{ row.editable ? '可编辑' : '只读' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="!row.editable || !canEdit"
            :title="row.editable ? (canEdit ? '' : '缺少 sys:config:edit 权限') : '该参数由系统维护，只读'"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无系统参数" :image-size="90" />
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="`编辑参数 · ${editing?.description ?? ''}`" width="520px" destroy-on-close>
      <el-alert v-if="inputError" :title="inputError" type="error" show-icon :closable="false" class="form-error" />
      <el-form label-width="90px">
        <el-form-item label="参数键">
          <span class="muted">{{ editing?.key }}</span>
        </el-form-item>
        <el-form-item label="类型">
          <span>{{ editing ? typeLabel(editing.valueType) : '' }}</span>
        </el-form-item>
        <el-form-item label="参数值" required>
          <el-input
            v-if="editing?.valueType === 'DECIMAL' || editing?.valueType === 'INT'"
            v-model="draftValue"
            inputmode="decimal"
            :placeholder="editing ? configInputPlaceholder(editing.valueType) : ''"
          />
          <el-input
            v-else-if="editing?.valueType === 'DATE_LIST'"
            v-model="draftValue"
            type="textarea"
            :rows="3"
            :placeholder="editing ? configInputPlaceholder(editing.valueType) : ''"
          />
          <el-input
            v-else
            v-model="draftValue"
            type="textarea"
            :rows="3"
            :placeholder="editing ? configInputPlaceholder(editing.valueType) : ''"
          />
          <div v-if="editing?.valueType === 'DATE_LIST'" class="field-hint">
            逗号分隔的 ISO 日期（YYYY-MM-DD）；保存前会逐个校验
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
.list-error,
.form-error {
  margin-bottom: 12px;
}
.config-name {
  font-weight: 500;
  color: #303133;
}
.config-key {
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.config-value {
  font-variant-numeric: tabular-nums;
  word-break: break-all;
}
.config-value.is-readonly {
  color: #a8abb2;
}
.config-warning {
  color: #e6a23c;
  font-size: 12px;
  line-height: 18px;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  line-height: 18px;
  margin-top: 2px;
}
</style>
