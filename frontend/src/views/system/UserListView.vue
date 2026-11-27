<script setup lang="ts">
/**
 * 用户管理（sys:user:view / sys:user:edit）。
 * 密码只在创建/重置时提交，响应不回显；用户名与主体创建后不可改。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import * as systemApi from '@/api/system'
import { isApiError } from '@/api/client'
import { createLatestOnly } from '@/utils/latest'
import type { Role, User, UserCreateRequest } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import { useOrgOptions } from '@/composables/useOrgOptions'
import { dataScopeLabel, validatePassword } from '@/utils/system'
import { listDepts } from '@/api/org'
import { buildDeptTree, type DeptNode } from '@/utils/labels'

const auth = useAuthStore()
const canEdit = computed(() => auth.has('sys:user:edit'))
/** 角色下拉依赖 GET /roles（需要 sys:role:view）；没有权限时不要静默 403 */
const canViewRoles = computed(() => auth.has('sys:role:view'))

const { companies, deptTree, loadCompanies, loadDepts } = useOrgOptions()

/** 用户弹窗自己的部门树：按「被编辑用户的主体」加载，不与左侧筛选器共用（I-3） */
const formDeptTree = ref<DeptNode[]>([])
const loadingFormDepts = ref(false)

async function loadFormDepts(companyId: string): Promise<void> {
  if (!companyId) {
    formDeptTree.value = []
    return
  }
  loadingFormDepts.value = true
  try {
    formDeptTree.value = buildDeptTree(await listDepts(companyId))
  } catch (error) {
    console.error('[payroll] 用户表单部门加载失败', error)
    ElMessage.warning('用户所属主体的部门加载失败，部门暂不可选')
    formDeptTree.value = []
  } finally {
    loadingFormDepts.value = false
  }
}

const filters = reactive({ keyword: '', companyId: '', deptId: '', status: '' as 0 | 1 | '' })
const rows = ref<User[]>([])
const roles = ref<Role[]>([])
const total = ref(0)
const current = ref(1)
const size = ref(20)
const loading = ref(false)
const loadError = ref('')
/** 列表请求竞态守卫（I-8） */
const loadGuard = createLatestOnly()

const dialogVisible = ref(false)
const editing = ref<User | null>(null)
const submitting = ref(false)
const formError = ref('')
const form = reactive({
  username: '',
  password: '',
  realName: '',
  companyId: '',
  deptId: '',
  employeeId: '',
  status: 1 as 0 | 1,
  roleIds: [] as string[],
})

const resetVisible = ref(false)
const resetTarget = ref<User | null>(null)
const newPassword = ref('')
const resetting = ref(false)
const resetError = ref('')

const query = computed(() => ({
  current: current.value,
  size: size.value,
  ...(filters.keyword.trim() ? { keyword: filters.keyword.trim() } : {}),
  ...(filters.companyId ? { companyId: filters.companyId } : {}),
  ...(filters.deptId ? { deptId: filters.deptId } : {}),
  ...(filters.status !== '' ? { status: filters.status } : {}),
}))

async function load() {
  const token = loadGuard.next()
  loading.value = true
  loadError.value = ''
  try {
    const page = await systemApi.listUsers(query.value)
    if (!loadGuard.isLatest(token)) return
    rows.value = page.records
    total.value = page.total
    current.value = page.current
    size.value = page.size
  } catch (error) {
    if (!loadGuard.isLatest(token)) return
    rows.value = []
    loadError.value = isApiError(error) ? error.message : '用户列表加载失败'
    ElMessage.error(loadError.value)
  } finally {
    if (loadGuard.isLatest(token)) loading.value = false
  }
}

async function loadRoles(companyId = filters.companyId || auth.companyId) {
  if (!canViewRoles.value) {
    roles.value = []
    return
  }
  try {
    roles.value = await systemApi.listRoles(companyId ? { companyId } : {})
  } catch (error) {
    console.error('[payroll] 角色列表加载失败', error)
    roles.value = []
    ElMessage.warning('角色列表加载失败，暂时无法分配角色')
  }
}

function search() {
  current.value = 1
  void load()
}

function resetFilters() {
  filters.keyword = ''
  filters.companyId = ''
  filters.deptId = ''
  filters.status = ''
  search()
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    username: '',
    password: '',
    realName: '',
    companyId: auth.companyId,
    deptId: '',
    employeeId: '',
    status: 1,
    roleIds: [],
  })
  formError.value = ''
  dialogVisible.value = true
  void loadRoles(auth.companyId)
  void loadFormDepts(auth.companyId)
}

function openEdit(row: User) {
  editing.value = row
  Object.assign(form, {
    username: row.username,
    password: '',
    realName: row.realName,
    companyId: row.companyId,
    deptId: row.deptId ?? '',
    employeeId: row.employeeId ?? '',
    status: row.status,
    roleIds: [...row.roleIds],
  })
  formError.value = ''
  dialogVisible.value = true
  void loadRoles(row.companyId)
  void loadFormDepts(row.companyId)
}

async function submit() {
  formError.value = ''
  if (!form.realName.trim()) {
    formError.value = '请输入姓名'
    return
  }
  if (!editing.value && !form.username.trim()) {
    formError.value = '请输入用户名'
    return
  }
  if (!editing.value) {
    const passwordError = validatePassword(form.password)
    if (passwordError) {
      formError.value = passwordError
      return
    }
  }

  submitting.value = true
  try {
    if (editing.value) {
      const updated = await systemApi.updateUser(editing.value.id, {
        realName: form.realName.trim(),
        deptId: form.deptId || null,
        employeeId: form.employeeId || null,
        status: form.status,
        roleIds: form.roleIds,
      })
      ElMessage.success(`已更新用户 ${updated.username}`)
      // 改到自己账号（角色/停用状态）时刷新权限点，菜单与按钮立即同步（I-11）
      if (editing.value.id === auth.user?.userId) {
        await auth.fetchMe().catch((error) => console.error('[payroll] 刷新当前用户权限失败', error))
      }
    } else {
      const payload: UserCreateRequest = {
        companyId: form.companyId,
        username: form.username.trim(),
        password: form.password,
        realName: form.realName.trim(),
        deptId: form.deptId || null,
        employeeId: form.employeeId || null,
        status: form.status,
        roleIds: form.roleIds,
      }
      const created = await systemApi.createUser(payload)
      ElMessage.success(`已创建用户 ${created.username}`)
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

function openReset(row: User) {
  resetTarget.value = row
  newPassword.value = ''
  resetError.value = ''
  resetVisible.value = true
}

async function submitReset() {
  if (!resetTarget.value) return
  const passwordError = validatePassword(newPassword.value)
  if (passwordError) {
    resetError.value = passwordError
    return
  }
  resetting.value = true
  try {
    await systemApi.resetUserPassword(resetTarget.value.id, { newPassword: newPassword.value })
    ElMessage.success('密码已重置，该用户下次请求需重新登录')
    resetVisible.value = false
  } catch (error) {
    resetError.value = isApiError(error) ? error.message : '重置失败'
    ElMessage.error(resetError.value)
  } finally {
    resetting.value = false
  }
}

async function remove(row: User) {
  try {
    await ElMessageBox.confirm(`确认删除用户「${row.username}」？账号将被逻辑删除，历史记录保留。`, '删除用户', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await systemApi.deleteUser(row.id)
    ElMessage.success('用户已删除')
    await load()
  } catch (error) {
    ElMessage.error(isApiError(error) ? error.message : '删除失败')
  }
}

watch(
  () => filters.companyId,
  (companyId) => {
    filters.deptId = ''
    void loadDepts(companyId || auth.companyId)
  },
)

watch(
  () => form.companyId,
  (companyId) => {
    if (!dialogVisible.value) return
    form.deptId = ''
    form.roleIds = []
    void loadFormDepts(companyId)
    void loadRoles(companyId)
  },
)

onMounted(() => {
  void load()
  void loadCompanies()
  void loadDepts(auth.companyId)
  void loadRoles()
})
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-input v-model="filters.keyword" placeholder="用户名或姓名" clearable style="width: 170px" @keyup.enter="search" />
        <el-select v-model="filters.companyId" clearable placeholder="法人主体" style="width: 160px">
          <el-option v-for="company in companies" :key="company.id" :value="company.id" :label="company.companyName" />
        </el-select>
        <el-tree-select
          v-model="filters.deptId"
          :data="deptTree"
          check-strictly
          clearable
          placeholder="部门"
          style="width: 160px"
          :render-after-expand="false"
        />
        <el-select v-model="filters.status" clearable placeholder="状态" style="width: 110px">
          <el-option :value="1" label="启用" />
          <el-option :value="0" label="停用" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="list-error" />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="realName" label="姓名" width="120" />
      <el-table-column prop="companyId" label="主体" width="90" />
      <el-table-column prop="deptId" label="部门" width="90" />
      <el-table-column prop="employeeId" label="关联人员" width="100">
        <template #default="{ row }">{{ row.employeeId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="mobileMask" label="手机号" width="130">
        <template #default="{ row }">{{ row.mobileMask || '—' }}</template>
      </el-table-column>
      <el-table-column label="数据范围" width="140">
        <template #default="{ row }">{{ dataScopeLabel(row.dataScope) }}</template>
      </el-table-column>
      <el-table-column label="权限点" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="permission in (row.permissions ?? []).slice(0, 3)" :key="permission" size="small" type="info" class="perm-tag">
            {{ permission }}
          </el-tag>
          <el-tag v-if="(row.permissions ?? []).length > 3" size="small" type="info">+{{ (row.permissions ?? []).length - 3 }}</el-tag>
          <span v-if="(row.permissions ?? []).length === 0" class="muted">无</span>
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
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" @click="openReset(row)">重置密码</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
          <span v-else class="muted">只读</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有符合条件的用户" :image-size="90" />
      </template>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="current"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="search"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑用户' : '新增用户'" width="620px" destroy-on-close>
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" class="form-error" />
      <el-form label-width="96px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="!!editing" maxlength="64" />
          <div v-if="editing" class="field-hint">用户名创建后不可修改</div>
        </el-form-item>
        <el-form-item v-if="!editing" label="初始密码" required>
          <el-input v-model="form.password" type="password" show-password maxlength="128" placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.realName" maxlength="64" />
        </el-form-item>
        <el-form-item label="法人主体" required>
          <el-select v-model="form.companyId" :disabled="!!editing" style="width: 100%">
            <el-option v-for="company in companies" :key="company.id" :value="company.id" :label="company.companyName" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门">
          <el-tree-select
            v-model="form.deptId"
            :data="formDeptTree"
            :loading="loadingFormDepts"
            check-strictly
            clearable
            :render-after-expand="false"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="关联人员">
          <el-input v-model="form.employeeId" placeholder="人员 ID（可空）" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="form.roleIds"
            multiple
            collapse-tags
            :disabled="!canViewRoles"
            style="width: 100%"
            :placeholder="canViewRoles ? '可多选' : '无角色查看权限'"
          >
            <el-option v-for="role in roles" :key="role.id" :value="role.id" :label="`${role.roleName}（${role.roleCode}）`" />
          </el-select>
          <div v-if="!canViewRoles" class="field-hint">当前账号缺少 sys:role:view 权限，无法分配角色</div>
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

    <el-dialog v-model="resetVisible" title="重置密码" width="420px" destroy-on-close>
      <el-alert v-if="resetError" :title="resetError" type="error" show-icon :closable="false" class="form-error" />
      <el-alert
        :title="`重置后 ${resetTarget?.username ?? ''} 的旧密码立即失效，下次请求需重新登录。`"
        type="warning"
        show-icon
        :closable="false"
        class="form-error"
      />
      <el-form label-width="90px">
        <el-form-item label="新密码" required>
          <el-input v-model="newPassword" type="password" show-password maxlength="128" placeholder="至少 8 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="submitReset">确认重置</el-button>
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
.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
