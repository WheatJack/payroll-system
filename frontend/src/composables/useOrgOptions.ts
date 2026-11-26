import { ref } from 'vue'
import { listCompanies, listDepts } from '@/api/org'
import type { Company } from '@/api/models'
import { buildDeptTree, type DeptNode } from '@/utils/labels'

/** 报表/系统页共用的主体与部门选项加载。 */
export function useOrgOptions() {
  const companies = ref<Company[]>([])
  const deptTree = ref<DeptNode[]>([])
  const loadingCompanies = ref(false)
  const loadingDepts = ref(false)

  async function loadCompanies(): Promise<void> {
    loadingCompanies.value = true
    try {
      companies.value = await listCompanies()
    } catch {
      companies.value = []
    } finally {
      loadingCompanies.value = false
    }
  }

  async function loadDepts(companyId: string): Promise<void> {
    if (!companyId) {
      deptTree.value = []
      return
    }
    loadingDepts.value = true
    try {
      deptTree.value = buildDeptTree(await listDepts(companyId))
    } catch {
      deptTree.value = []
    } finally {
      loadingDepts.value = false
    }
  }

  return { companies, deptTree, loadingCompanies, loadingDepts, loadCompanies, loadDepts }
}
