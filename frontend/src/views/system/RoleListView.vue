<script setup lang="ts">
/**
 * 角色与授权（sys:role:view / sys:role:edit）。
 * 权限点目录来自 GET /permissions（不硬编码）；授予 ALL 需 sys:role:grant:all。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import * as systemApi from '@/api/system'
import { isApiError } from '@/api/client'
import type { DataScope, PermissionItem, Role } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import { useOrgOptions } from '@/composables/useOrgOptions'
import { dataScopeLabel, groupPermissions, requiresGrantAll } from '@/utils/system'

const auth = useAuthStore()
const canEdit = computed(() => auth.has('sys:role:edit'))
const canGrantAll = computed(() => auth.has('sys:role:grant:all'))

const { companies, loadCompanies } = useOrgOptions()

const filters = reactive({ companyId: '', status: '' as 0 | 1 | '' })
const rows = ref<Role[]>([])
const permissions = ref<PermissionItem[]>([])
const loading = ref(false)
const loadError = ref('')

const permissionGroups = computed(() => groupPermissions(permissions.value))

const dialogVisible = ref(false)
const editing = ref<Role | null>(null)
const submitting = ref(false)
const formError = ref('')
const form = reactive({
  roleCode: '',
  roleName: '',
  dataScope: 'COMPANY' as DataScope,
  remark: '',
  status: 1 as 0 | 1,
})

const grantVisible = ref(false)
const grantTarget = ref<Role | null>(null)
const grantSelection = ref<string[]>([])
const granting = ref(false)
const grantError = ref('')

const DATA_SCOPES: DataScope[] = ['ALL', 'COMPANY', 'DEPT_SUB', 'DEPT', 'SELF']

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await systemApi.listRoles({
      ...(filters.companyId ? { companyId: filters.companyId } : {}),
      ...(filters.status !== '' ? { status: filters.status } : {}),
    })
  } catch (error) {
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '角色列表加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

async function loadPermissions() {
  try {
    permissions.value = await systemApi.listPermissions()
  } catch {
    permissions.value = []
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    roleCode: '',
    roleName: '',
    dataScope: 'COMPANY',
    remark: '',
    status: 1,
  })
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(row: Role) {
  editing.value = row
  Object.assign(form, {
    roleCode: row.roleCode,
    roleName: row.roleName,
    dataScope: row.dataScope,
    remark: row.remark ?? '',
    status: row.status,
  })
  formError.value = ''
  dialogVisible.value = true
}

async function submit() {
  formError.value = ''
  if (!form.roleCode.trim() || !form.roleName.trim()) {
    formError.value = '角色编码与名称必填'
    return
  }
  if (requiresGrantAll(form.dataScope) && !canGrantAll.value) {
    formError.value = '授予「集团全部主体」需要 sys:role:grant:all 权限'
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      await systemApi.updateRole(editing.value.id, {
        roleCode: form.roleCode.trim(),
        roleName: form.roleName.trim(),
        dataScope: form.dataScope,
        remark: form.remark || null,
        status: form.status,
      })
      ElMessage.success('角色已保存')
    } else {
      await systemApi.createRole({
        companyId: filters.companyId || auth.companyId,
        roleCode: form.roleCode.trim(),
        roleName: form.roleName.trim(),
        dataScope: form.dataScope,
        remark: form.remark || null,
        status: form.status,
        permissions: [],
      })
      ElMessage.success('角色已创建，可继续为其授权')
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

function openGrant(row: Role) {
  grantTarget.value = row
  grantSelection.value = [...row.permissions]
  grantError.value = ''
  grantVisible.value = true
}

function toggleModule(group: ReturnType<typeof groupPermissions>[number], checked: boolean) {
  const codes = group.items.map((item) => item.code)
  const set = new Set(grantSelection.value)
  if (checked) codes.forEach((code) => set.add(code))
  else codes.forEach((code) => set.delete(code))
  grantSelection.value = [...set]
}

function moduleChecked(group: ReturnType<typeof groupPermissions>[number]): boolean {
  return group.items.every((item) => grantSelection.value.includes(item.code))
}

function moduleIndeterminate(group: ReturnType<typeof groupPermissions>[number]): boolean {
  const selected = group.items.filter((item) => grantSelection.value.includes(item.code)).length
  return selected > 0 && selected < group.items.length
}

async function submitGrant() {
  if (!grantTarget.value) return
  grantError.value = ''
  granting.value = true
  try {
    await systemApi.replaceRoleGrants(grantTarget.value.id, grantSelection.value)
    ElMessage.success('权限点已更新')
    grantVisible.value = false
    await load()
  } catch (error) {
    grantError.value = isApiError(error) ? error.message : '授权失败'
    ElMessage.error(grantError.value)
  } finally {
    granting.value = false
  }
}

async function remove(row: Role) {
  try {
    await ElMessageBox.confirm(`确认删除角色「${row.roleName}」？仍有用户关联时会被拒绝。`, '删除角色', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await systemApi.deleteRole(row.id)
    ElMessage.success('角色已删除')
    await load()
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '删除失败')
  }
}

onMounted(() => {
  void load()
  void loadPermissions()
  void loadCompanies()
})
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-select v-model="filters.companyId" clearable placeholder="法人主体" style="width: 170px">
          <el-option v-for="company in companies" :key="company.id" :value="company.id" :label="company.companyName" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="状态" style="width: 110px">
          <el-option :value="1" label="启用" />
          <el-option :value="0" label="停用" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openCreate">新增角色</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="list-error" />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="roleCode" label="角色编码" width="150" />
      <el-table-column prop="roleName" label="角色名称" width="150" />
      <el-table-column label="数据范围" width="150">
        <template #default="{ row }">{{ dataScopeLabel(row.dataScope) }}</template>
      </el-table-column>
      <el-table-column label="权限点" min-width="260">
        <template #default="{ row }">
          <el-tag v-for="permission in row.permissions.slice(0, 4)" :key="permission" size="small" type="info" class="perm-tag">
            {{ permission }}
          </el-tag>
          <el-tag v-if="row.permissions.length > 4" size="small" type="info">+{{ row.permissions.length - 4 }}</el-tag>
          <span v-if="row.permissions.length === 0" class="muted">未授权</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <template v-if="canEdit">
            <el-button link type="primary" @click="openGrant(row)">授权</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
          <span v-else class="muted">只读</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无角色" :image-size="90" />
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑角色' : '新增角色'" width="560px" destroy-on-close>
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" class="form-error" />
      <el-form label-width="96px">
        <el-form-item label="角色编码" required>
          <el-input v-model="form.roleCode" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="form.roleName" maxlength="64" />
        </el-form-item>
        <el-form-item label="数据范围" required>
          <el-select v-model="form.dataScope" style="width: 100%">
            <el-option v-for="scope in DATA_SCOPES" :key="scope" :value="scope" :label="dataScopeLabel(scope)" />
          </el-select>
          <div v-if="requiresGrantAll(form.dataScope)" class="field-hint warn">
            授予「集团全部主体」需要 sys:role:grant:all 权限（防自我提权）
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
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

    <el-dialog v-model="grantVisible" :title="`授权 · ${grantTarget?.roleName ?? ''}`" width="640px" destroy-on-close>
      <el-alert v-if="grantError" :title="grantError" type="error" show-icon :closable="false" class="form-error" />
      <el-alert
        title="提交的权限点必须是操作者自身权限点的子集；超出部分后端返回 403（防自我提权）。"
        type="info"
        show-icon
        :closable="false"
        class="form-error"
      />
      <div v-for="group in permissionGroups" :key="group.module" class="perm-group">
        <div class="perm-group-head">
          <el-checkbox
            :model-value="moduleChecked(group)"
            :indeterminate="moduleIndeterminate(group)"
            @update:model-value="(checked: boolean | string | number) => toggleModule(group, Boolean(checked))"
          >
            {{ group.label }}
          </el-checkbox>
        </div>
        <el-checkbox-group v-model="grantSelection" class="perm-group-body">
          <el-checkbox v-for="item in group.items" :key="item.code" :value="item.code">
            {{ item.name }}
            <span class="perm-code">{{ item.code }}</span>
          </el-checkbox>
        </el-checkbox-group>
      </div>
      <template #footer>
        <span class="selected-count muted">已选 {{ grantSelection.length }} 项</span>
        <el-button @click="grantVisible = false">取消</el-button>
        <el-button type="primary" :loading="granting" @click="submitGrant">保存授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.list-error,
.form-error {
  margin-bottom: 12px;
}
.perm-tag {
  margin-right: 4px;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  line-height: 18px;
}
.field-hint.warn {
  color: #e6a23c;
}
.perm-group {
  border-top: 1px solid #ebeef5;
  padding: 10px 0;
}
.perm-group-head {
  font-weight: 600;
  margin-bottom: 6px;
}
.perm-group-body {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
}
.perm-code {
  color: #a8abb2;
  font-size: 12px;
  margin-left: 4px;
}
.selected-count {
  margin-right: 12px;
  font-size: 12px;
}
</style>
