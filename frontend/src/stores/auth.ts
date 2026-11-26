import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from '@/api/auth'
import { clearToken, getToken, setToken } from '@/api/client'
import type { CurrentUser } from '@/api/models'

/**
 * 登录态与权限点。
 *
 * `permissions` 是后端 `/auth/me` 返回的权限点字符串数组（见详细设计 §4.7），
 * 权限判断只做字符串匹配，不在前端推导角色。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getToken())
  const user = ref<CurrentUser | null>(null)
  const permissions = ref<string[]>([])
  const loading = ref(false)

  const isLoggedIn = computed(() => token.value !== '')
  const realName = computed(() => user.value?.realName ?? '')
  const companyId = computed(() => user.value?.companyId ?? '')

  function has(perm: string): boolean {
    return permissions.value.includes(perm)
  }

  function hasAny(perms: string[]): boolean {
    return perms.some((perm) => has(perm))
  }

  function applyUser(next: CurrentUser): void {
    user.value = next
    permissions.value = [...(next.permissions ?? [])]
  }

  async function login(username: string, password: string): Promise<CurrentUser> {
    loading.value = true
    try {
      const response = await authApi.login({ username, password })
      token.value = response.token
      setToken(response.token)
      applyUser(response.user)
      return response.user
    } finally {
      loading.value = false
    }
  }

  async function fetchMe(): Promise<CurrentUser> {
    const current = await authApi.fetchCurrentUser()
    applyUser(current)
    return current
  }

  function logout(): void {
    token.value = ''
    user.value = null
    permissions.value = []
    clearToken()
  }

  return {
    token,
    user,
    permissions,
    loading,
    isLoggedIn,
    realName,
    companyId,
    has,
    hasAny,
    login,
    fetchMe,
    logout,
    applyUser,
  }
})
