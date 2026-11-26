<script setup lang="ts">
/**
 * 法人主体管理（sys:company:edit）。
 * 主体编码全局唯一，重复时后端返回 409。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import * as systemApi from '@/api/system'
import { listCompanies } from '@/api/org'
import { isApiError } from '@/api/client'
import type { Company } from '@/api/models'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canEdit = computed(() => auth.has('sys:company:edit'))

const rows = ref<Company[]>([])
const loading = ref(false)
const loadError = ref('')

const dialogVisible = ref(false)
const editing = ref<Company | null>(null)
const submitting = ref(false)
const formError = ref('')
const form = reactive({ companyCode: '', companyName: '', status: 1 as 0 | 1 })

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await listCompanies()
  } catch (error) {
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '主体列表加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { companyCode: '', companyName: '', status: 1 })
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(row: Company) {
  editing.value = row
  Object.assign(form, { companyCode: row.companyCode, companyName: row.companyName, status: row.status })
  formError.value = ''
  dialogVisible.value = true
}

async function submit() {
  formError.value = ''
  if (!form.companyCode.trim() || !form.companyName.trim()) {
    formError.value = '主体编码与名称必填'
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      await systemApi.updateCompany(editing.value.id, {
        companyCode: form.companyCode.trim(),
        companyName: form.companyName.trim(),
        status: form.status,
      })
      ElMessage.success('主体已保存')
    } else {
      await systemApi.createCompany({
        companyCode: form.companyCode.trim(),
        companyName: form.companyName.trim(),
        status: form.status,
      })
      ElMessage.success('主体已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    formError.value = isApiError(error) ? error.message : '保存失败'
    ElMessage.error(formError.value)
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
        <span class="muted">主体编码全局唯一；期间与工资表按主体隔离</span>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openCreate">新增主体</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="list-error" />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="companyCode" label="主体编码" width="160" />
      <el-table-column prop="companyName" label="主体名称" min-width="220" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEdit" link type="primary" @click="openEdit(row)">编辑</el-button>
          <span v-else class="muted">只读</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无法人主体" :image-size="90" />
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑主体' : '新增主体'" width="520px" destroy-on-close>
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" class="form-error" />
      <el-form label-width="96px">
        <el-form-item label="主体编码" required>
          <el-input v-model="form.companyCode" maxlength="32" />
        </el-form-item>
        <el-form-item label="主体名称" required>
          <el-input v-model="form.companyName" maxlength="128" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
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
</style>
