/**
 * 人员批量导入的状态机（与工资数据导入同构，但对象是人员 + 薪酬档案）。
 *
 * 关键约束：`failRows > 0` 时禁止提交——按钮置灰并给出原因，后端 409 兜底。
 */
import { computed, ref } from 'vue'
import * as importApi from '@/api/import'
import { isApiError } from '@/api/client'
import type { ImportCommitResult } from '@/api/models'
import { useAuthStore } from '@/stores/auth'
import { canCommit, commitBlockedReason, type ImportBatchView } from '@/utils/import'

export function useEmployeeImport() {
  const auth = useAuthStore()

  const batch = ref<ImportBatchView | null>(null)
  const validating = ref(false)
  const committing = ref(false)
  const errorMessage = ref('')
  const commitResult = ref<ImportCommitResult | null>(null)

  const canSubmit = computed(() => auth.has('payroll:import:submit'))
  const blockedReason = computed(() => commitBlockedReason(batch.value, canSubmit.value))
  const commitEnabled = computed(() => canCommit(batch.value, canSubmit.value) && !committing.value)

  async function validate(file: File): Promise<ImportBatchView | null> {
    errorMessage.value = ''
    commitResult.value = null
    validating.value = true
    try {
      const result = await importApi.validateEmployeeImport(file)
      batch.value = result
      if (result.status === 'VALIDATING') {
        errorMessage.value = '预校验仍在进行中，请稍后重新上传校验'
      }
      return result
    } catch (error) {
      batch.value = null
      errorMessage.value = isApiError(error) ? error.message : '预校验失败，请稍后重试'
      return null
    } finally {
      validating.value = false
    }
  }

  async function commit(): Promise<ImportCommitResult | null> {
    if (!batch.value) return null
    const reason = commitBlockedReason(batch.value, canSubmit.value)
    if (reason) {
      errorMessage.value = reason
      return null
    }
    committing.value = true
    errorMessage.value = ''
    try {
      const result = await importApi.commitEmployeeImport(batch.value.batchId)
      commitResult.value = result
      batch.value = { ...batch.value, status: 'COMMITTED' }
      return result
    } catch (error) {
      if (isApiError(error) && error.code === 409) {
        const data = error.data as { batchId?: string; failRows?: number } | null
        if (data && typeof data.failRows === 'number' && data.failRows > 0) {
          errorMessage.value = `预校验有 ${data.failRows} 行失败，禁止提交；请修正后重新上传`
        } else {
          errorMessage.value = error.message || '该批次已提交或存在冲突'
        }
      } else {
        errorMessage.value = isApiError(error) ? error.message : '提交失败，请稍后重试'
      }
      return null
    } finally {
      committing.value = false
    }
  }

  function reset(): void {
    batch.value = null
    errorMessage.value = ''
    commitResult.value = null
  }

  return {
    batch,
    validating,
    committing,
    errorMessage,
    commitResult,
    canSubmit,
    blockedReason,
    commitEnabled,
    validate,
    commit,
    reset,
  }
}
