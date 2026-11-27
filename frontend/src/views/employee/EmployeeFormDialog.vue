<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import * as employeeApi from '@/api/employee'
import { isApiError } from '@/api/client'
import type { Employee, EmployeeCreateRequest, EmployeeType, EmployeeUpdateRequest } from '@/api/models'
import type { DeptNode } from '@/utils/labels'
import { employeeSeniority, employeeSocialSeniority } from '@/utils/employee'

const props = defineProps<{
  modelValue: boolean
  /** 为空表示新增 */
  employee: Employee | null
  deptTree: DeptNode[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [employee: Employee]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const isEdit = computed(() => props.employee !== null)
const formRef = ref<FormInstance>()
const submitting = ref(false)
const serverError = ref('')

interface EmployeeForm {
  empNo: string
  name: string
  gender: 0 | 1 | 2
  idType: string
  idNo: string
  mobile: string
  employeeType: EmployeeType
  jobLevel: string
  positionName: string
  deptId: string
  hireDate: string
  workStartDate: string
  leaveDate: string
  status: 1 | 2 | 3
  remark: string
}

const EMPTY_FORM: EmployeeForm = {
  empNo: '',
  name: '',
  gender: 0,
  idType: 'ID_CARD',
  idNo: '',
  mobile: '',
  employeeType: 'REGULAR',
  jobLevel: '',
  positionName: '',
  deptId: '',
  hireDate: '',
  workStartDate: '',
  leaveDate: '',
  status: 1,
  remark: '',
}

const form = reactive<EmployeeForm>({ ...EMPTY_FORM })

const rules = computed<FormRules>(() => ({
  empNo: [{ required: true, message: '请输入工号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  employeeType: [{ required: true, message: '请选择员工类型', trigger: 'change' }],
  deptId: [{ required: true, message: '请选择部门', trigger: 'change' }],
  hireDate: [{ required: true, message: '请选择入职日期', trigger: 'change' }],
  idNo: isEdit.value
    ? []
    : [{ required: true, message: '请输入证件号码', trigger: 'blur' }],
}))

function reset() {
  serverError.value = ''
  const source = props.employee
  if (source) {
    Object.assign(form, {
      empNo: source.empNo,
      name: source.name,
      gender: source.gender as 0 | 1 | 2,
      idType: source.idType,
      idNo: '',
      mobile: '',
      employeeType: source.employeeType,
      jobLevel: source.jobLevel ?? '',
      positionName: source.positionName ?? '',
      deptId: source.deptId,
      hireDate: source.hireDate,
      workStartDate: source.workStartDate ?? '',
      leaveDate: source.leaveDate ?? '',
      status: source.status as 1 | 2 | 3,
      remark: source.remark ?? '',
    } satisfies EmployeeForm)
  } else {
    Object.assign(form, EMPTY_FORM)
  }
  formRef.value?.clearValidate()
}

watch(
  () => [props.modelValue, props.employee?.id] as const,
  ([open]) => {
    if (open) reset()
  },
  { immediate: true },
)

/** 修改时只提交真正变化的字段（契约：只提交需要修改的字段）。 */
function buildUpdatePayload(source: Employee): EmployeeUpdateRequest {
  const payload: Record<string, unknown> = {}
  const assign = (key: keyof EmployeeUpdateRequest, next: unknown, prev: unknown) => {
    if (next !== prev) payload[key] = next
  }
  assign('empNo', form.empNo, source.empNo)
  assign('name', form.name, source.name)
  assign('gender', form.gender, source.gender)
  assign('idType', form.idType, source.idType)
  assign('employeeType', form.employeeType, source.employeeType)
  assign('jobLevel', form.jobLevel || null, source.jobLevel ?? null)
  assign('positionName', form.positionName || null, source.positionName ?? null)
  assign('deptId', form.deptId, source.deptId)
  assign('hireDate', form.hireDate, source.hireDate)
  assign('workStartDate', form.workStartDate || null, source.workStartDate ?? null)
  assign('leaveDate', form.leaveDate || null, source.leaveDate ?? null)
  assign('status', form.status, source.status)
  assign('remark', form.remark || null, source.remark ?? null)
  if (form.idNo.trim() !== '') payload.idNo = form.idNo.trim()
  if (form.mobile.trim() !== '') payload.mobile = form.mobile.trim()
  return payload as EmployeeUpdateRequest
}

async function submit() {
  serverError.value = ''
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    let saved: Employee
    if (props.employee) {
      const payload = buildUpdatePayload(props.employee)
      if (Object.keys(payload).length === 0) {
        ElMessage.info('没有需要保存的修改')
        visible.value = false
        return
      }
      saved = await employeeApi.updateEmployee(props.employee.id, payload)
    } else {
      const payload: EmployeeCreateRequest = {
        empNo: form.empNo.trim(),
        name: form.name.trim(),
        gender: form.gender,
        idType: form.idType,
        idNo: form.idNo.trim(),
        employeeType: form.employeeType,
        deptId: form.deptId,
        hireDate: form.hireDate,
        status: form.status,
        jobLevel: form.jobLevel || null,
        positionName: form.positionName || null,
        workStartDate: form.workStartDate || null,
        leaveDate: form.leaveDate || null,
        remark: form.remark || null,
      }
      if (form.mobile.trim() !== '') payload.mobile = form.mobile.trim()
      saved = await employeeApi.createEmployee(payload)
    }
    ElMessage.success(isEdit.value ? '已保存人员信息' : '人员已创建')
    emit('saved', saved)
    visible.value = false
  } catch (error) {
    // 409 的 msg 由后端给出：工号已被占用 / 该证件号码已存在人员记录
    serverError.value = isApiError(error) ? error.message : '保存失败，请稍后重试'
    ElMessage.error(serverError.value)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="isEdit ? '编辑人员' : '新增人员'" width="720px" destroy-on-close>
    <el-alert v-if="serverError" :title="serverError" type="error" show-icon :closable="false" class="form-error" />
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="工号" prop="empNo">
            <el-input v-model="form.empNo" maxlength="32" placeholder="全局唯一" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="姓名" prop="name">
            <el-input v-model="form.name" maxlength="64" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="性别" prop="gender">
            <el-select v-model="form.gender" class="full-width">
              <el-option :value="0" label="未知" />
              <el-option :value="1" label="男" />
              <el-option :value="2" label="女" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="员工类型" prop="employeeType">
            <el-select v-model="form.employeeType" class="full-width">
              <el-option value="REGULAR" label="正式" />
              <el-option value="PROBATION" label="试用" />
              <el-option value="INTERN" label="实习" />
              <el-option value="LABOR" label="劳务" />
              <el-option value="OUTSOURCE" label="外包" />
              <el-option value="REHIRE" label="返聘" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="证件类型" prop="idType">
            <el-input v-model="form.idType" maxlength="16" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="证件号码" prop="idNo">
            <el-input
              v-model="form.idNo"
              maxlength="32"
              :placeholder="isEdit ? '不修改请留空' : '仅提交一次，后端加密存储'"
            />
            <div v-if="isEdit" class="field-hint">当前：{{ employee?.idNoMask }}（不可反查，留空表示不修改）</div>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="手机号" prop="mobile">
            <el-input v-model="form.mobile" maxlength="20" placeholder="留空表示不修改/不填写" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="部门" prop="deptId">
            <el-tree-select
              v-model="form.deptId"
              :data="deptTree"
              check-strictly
              :render-after-expand="false"
              class="full-width"
              placeholder="请选择部门"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="职级" prop="jobLevel">
            <el-input v-model="form.jobLevel" maxlength="32" placeholder="如 P6" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="岗位" prop="positionName">
            <el-input v-model="form.positionName" maxlength="64" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="入职日期" prop="hireDate">
            <el-date-picker v-model="form.hireDate" type="date" value-format="YYYY-MM-DD" class="full-width" />
          </el-form-item>
        </el-col>
        <el-col v-if="isEdit" :span="12">
          <el-form-item label="工龄">
            <div class="readonly-value">
              {{ employee ? employeeSeniority(employee) : '—' }} 年
              <span class="field-hint inline">按入职日期计算，只读</span>
            </div>
          </el-form-item>
        </el-col>
        <el-col v-if="isEdit" :span="12">
          <el-form-item label="社会工龄">
            <div class="readonly-value">
              {{ employee ? employeeSocialSeniority(employee) : '—' }} 年
              <span class="field-hint inline">按参加工作时间计算，只读</span>
            </div>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="参加工作" prop="workStartDate">
            <el-date-picker
              v-model="form.workStartDate"
              type="date"
              value-format="YYYY-MM-DD"
              class="full-width"
              placeholder="用于工龄计算"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="离职日期" prop="leaveDate">
            <el-date-picker v-model="form.leaveDate" type="date" value-format="YYYY-MM-DD" class="full-width" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="在职状态" prop="status">
            <el-select v-model="form.status" class="full-width">
              <el-option :value="1" label="在职" />
              <el-option :value="2" label="离职" />
              <el-option :value="3" label="停薪留职" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="备注" prop="remark">
            <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.full-width {
  width: 100%;
}
.form-error {
  margin-bottom: 12px;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  line-height: 18px;
  margin-top: 2px;
}
.field-hint.inline {
  margin-left: 6px;
  margin-top: 0;
}
.readonly-value {
  font-size: 14px;
  color: #303133;
  font-variant-numeric: tabular-nums;
}
</style>
