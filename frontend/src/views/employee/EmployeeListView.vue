<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import * as employeeApi from '@/api/employee'
import { listDepts } from '@/api/org'
import { isApiError } from '@/api/client'
import type { Employee, EmployeeType } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import { buildDeptTree, employeeStatusLabel, employeeTypeLabel, genderLabel, type DeptNode } from '@/utils/labels'
import EmployeeFormDialog from './EmployeeFormDialog.vue'
import CompProfilePanel from './CompProfilePanel.vue'

const auth = useAuthStore()
const canEdit = computed(() => auth.has('payroll:record:edit'))

const filters = reactive({
  keyword: '',
  deptId: '',
  employeeType: '' as EmployeeType | '',
  status: '' as 1 | 2 | 3 | '',
})

const rows = ref<Employee[]>([])
const total = ref(0)
const current = ref(1)
const size = ref(20)
const loading = ref(false)
const loadError = ref('')

const deptTree = ref<DeptNode[]>([])

const dialogVisible = ref(false)
const editing = ref<Employee | null>(null)

const drawerVisible = ref(false)
const drawerEmployee = ref<Employee | null>(null)

const query = computed<employeeApi.EmployeeQuery>(() => {
  const result: employeeApi.EmployeeQuery = { current: current.value, size: size.value }
  if (filters.keyword.trim()) result.keyword = filters.keyword.trim()
  if (filters.deptId) result.deptId = filters.deptId
  if (filters.employeeType) result.employeeType = filters.employeeType
  if (filters.status !== '') result.status = filters.status
  return result
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const page = await employeeApi.listEmployees(query.value)
    rows.value = page.records
    total.value = page.total
    current.value = page.current
    size.value = page.size
  } catch (error) {
    rows.value = []
    total.value = 0
    loadError.value = isApiError(error) ? error.message : '人员列表加载失败'
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

async function loadDepts() {
  if (!auth.companyId) return
  try {
    deptTree.value = buildDeptTree(await listDepts(auth.companyId))
  } catch {
    deptTree.value = []
  }
}

function search() {
  current.value = 1
  void load()
}

function reset() {
  filters.keyword = ''
  filters.deptId = ''
  filters.employeeType = ''
  filters.status = ''
  current.value = 1
  void load()
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(row: Employee) {
  editing.value = row
  dialogVisible.value = true
}

function onSaved() {
  void load()
}

function openCompProfiles(row: Employee) {
  drawerEmployee.value = row
  drawerVisible.value = true
}

onMounted(() => {
  void load()
  void loadDepts()
})
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <el-input
          v-model="filters.keyword"
          placeholder="姓名或工号"
          clearable
          style="width: 180px"
          @keyup.enter="search"
        />
        <el-tree-select
          v-model="filters.deptId"
          :data="deptTree"
          check-strictly
          clearable
          placeholder="部门"
          style="width: 180px"
          :render-after-expand="false"
        />
        <el-select v-model="filters.employeeType" clearable placeholder="员工类型" style="width: 130px">
          <el-option value="REGULAR" label="正式" />
          <el-option value="PROBATION" label="试用" />
          <el-option value="INTERN" label="实习" />
          <el-option value="LABOR" label="劳务" />
          <el-option value="OUTSOURCE" label="外包" />
          <el-option value="REHIRE" label="返聘" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="在职状态" style="width: 130px">
          <el-option :value="1" label="在职" />
          <el-option :value="2" label="离职" />
          <el-option :value="3" label="停薪留职" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openCreate">新增人员</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="list-error" />

    <el-table v-loading="loading" :data="rows" border stripe size="default" height="calc(100vh - 240px)">
      <el-table-column prop="empNo" label="工号" width="110" fixed />
      <el-table-column prop="name" label="姓名" width="100" fixed />
      <el-table-column label="性别" width="70">
        <template #default="{ row }">{{ genderLabel(row.gender) }}</template>
      </el-table-column>
      <el-table-column prop="deptId" label="部门ID" width="90" />
      <el-table-column label="员工类型" width="100">
        <template #default="{ row }">{{ employeeTypeLabel(row.employeeType) }}</template>
      </el-table-column>
      <el-table-column prop="jobLevel" label="职级" width="80" />
      <el-table-column prop="positionName" label="岗位" min-width="120" show-overflow-tooltip />
      <el-table-column prop="hireDate" label="入职日期" width="110" />
      <el-table-column prop="idNoMask" label="证件号" width="130">
        <template #default="{ row }">
          <span class="masked">{{ row.idNoMask }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="mobileMask" label="手机号" width="130">
        <template #default="{ row }">
          <span class="masked">{{ row.mobileMask || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'info' : 'warning'" size="small">
            {{ employeeStatusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openCompProfiles(row)">薪酬档案</el-button>
          <el-button v-if="canEdit" link type="primary" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有符合条件的人员" :image-size="90" />
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

    <EmployeeFormDialog v-model="dialogVisible" :employee="editing" :dept-tree="deptTree" @saved="onSaved" />

    <el-drawer v-model="drawerVisible" :title="`薪酬档案 · ${drawerEmployee?.name ?? ''}`" size="680px" destroy-on-close>
      <CompProfilePanel
        v-if="drawerEmployee"
        :employee-id="drawerEmployee.id"
        :employee-name="drawerEmployee.name"
      />
    </el-drawer>
  </div>
</template>

<style scoped>
.list-error {
  margin-bottom: 12px;
}
.masked {
  font-variant-numeric: tabular-nums;
  color: #606266;
}
.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
