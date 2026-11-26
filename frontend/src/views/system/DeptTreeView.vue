<script setup lang="ts">
/**
 * 部门管理（sys:dept:edit）。
 * 契约返回扁平部门（parentId/ancestors），前端组装树用于展示；`ancestors` 由后端重算。
 * 删除有下级或关联员工/用户的部门时后端返回 409，前端展示原因而不是崩。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import * as systemApi from '@/api/system'
import { listDepts } from '@/api/org'
import { isApiError } from '@/api/client'
import type { Dept } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import { useOrgOptions } from '@/composables/useOrgOptions'
import { buildDeptTree, type DeptNode } from '@/utils/labels'

interface DeptRow extends Dept {
  children: DeptRow[]
}

const auth = useAuthStore()
const canEdit = computed(() => auth.has('sys:dept:edit'))
const { companies, loadCompanies } = useOrgOptions()

const companyId = ref(auth.companyId || '')
const rows = ref<DeptRow[]>([])
const treeOptions = ref<DeptNode[]>([])
const loading = ref(false)
const loadError = ref('')

const dialogVisible = ref(false)
const editing = ref<DeptRow | null>(null)
const submitting = ref(false)
const formError = ref('')
const form = reactive({
  parentId: '0',
  deptCode: '',
  deptName: '',
  sortNo: 0,
  status: 1 as 0 | 1,
})

function toRows(depts: Dept[]): DeptRow[] {
  const nodes = new Map<string, DeptRow>()
  for (const dept of depts) nodes.set(dept.id, { ...dept, children: [] })
  const roots: DeptRow[] = []
  for (const dept of depts) {
    const node = nodes.get(dept.id)!
    const parent = nodes.get(dept.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}

async function load() {
  if (!companyId.value) {
    rows.value = []
    treeOptions.value = []
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const depts = await listDepts(companyId.value)
    rows.value = toRows(depts)
    treeOptions.value = [{ value: '0', label: '（根部门）', children: buildDeptTree(depts) }]
  } catch (error) {
    rows.value = []
    treeOptions.value = []
    loadError.value = isApiError(error) ? error.message : '部门加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function openCreate(parent?: DeptRow) {
  editing.value = null
  Object.assign(form, {
    parentId: parent?.id ?? '0',
    deptCode: '',
    deptName: '',
    sortNo: 0,
    status: 1,
  })
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(row: DeptRow) {
  editing.value = row
  Object.assign(form, {
    parentId: row.parentId,
    deptCode: row.deptCode,
    deptName: row.deptName,
    sortNo: row.sortNo,
    status: row.status,
  })
  formError.value = ''
  dialogVisible.value = true
}

async function submit() {
  formError.value = ''
  if (!form.deptCode.trim() || !form.deptName.trim()) {
    formError.value = '部门编码与名称必填'
    return
  }
  if (editing.value && form.parentId === editing.value.id) {
    formError.value = '上级部门不能是自己'
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      await systemApi.updateDept(editing.value.id, {
        parentId: form.parentId,
        deptCode: form.deptCode.trim(),
        deptName: form.deptName.trim(),
        sortNo: form.sortNo,
        status: form.status,
      })
      ElMessage.success('部门已保存')
    } else {
      await systemApi.createDept({
        companyId: companyId.value,
        parentId: form.parentId || '0',
        deptCode: form.deptCode.trim(),
        deptName: form.deptName.trim(),
        sortNo: form.sortNo,
        status: form.status,
      })
      ElMessage.success('部门已创建')
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

async function remove(row: DeptRow) {
  try {
    await ElMessageBox.confirm(
      `确认删除部门「${row.deptName}」？存在下级部门或关联员工/用户时会被拒绝。`,
      '删除部门',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await systemApi.deleteDept(row.id)
    ElMessage.success('部门已删除')
    await load()
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '删除失败')
  }
}

watch(companyId, load)

onMounted(() => {
  void loadCompanies()
  void load()
})
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-select v-model="companyId" placeholder="法人主体" style="width: 190px">
          <el-option v-for="company in companies" :key="company.id" :value="company.id" :label="company.companyName" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <span class="muted">删除有下级或关联员工/用户的部门会被后端拒绝（409）</span>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openCreate()">新增根部门</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="list-error" />

    <el-table
      v-loading="loading"
      :data="rows"
      row-key="id"
      :tree-props="{ children: 'children' }"
      default-expand-all
      border
    >
      <el-table-column prop="deptName" label="部门名称" min-width="220" />
      <el-table-column prop="deptCode" label="部门编码" width="160" />
      <el-table-column prop="sortNo" label="排序" width="90" align="right" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <template v-if="canEdit">
            <el-button link type="primary" @click="openCreate(row)">新增下级</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
          <span v-else class="muted">只读</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="该主体暂无部门" :image-size="90" />
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑部门' : '新增部门'" width="520px" destroy-on-close>
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" class="form-error" />
      <el-form label-width="96px">
        <el-form-item label="上级部门">
          <el-tree-select
            v-model="form.parentId"
            :data="treeOptions"
            check-strictly
            :render-after-expand="false"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="部门编码" required>
          <el-input v-model="form.deptCode" maxlength="32" />
        </el-form-item>
        <el-form-item label="部门名称" required>
          <el-input v-model="form.deptName" maxlength="64" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="0" :step="10" />
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
