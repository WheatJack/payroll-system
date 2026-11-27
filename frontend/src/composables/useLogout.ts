import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 退出登录：清 token 并**跳转登录页**。
 * 只清 token 不导航会把用户留在原页面（页面数据还在，看起来像没退出）。
 */
export function useLogout() {
  const router = useRouter()
  const auth = useAuthStore()

  async function logout(): Promise<void> {
    auth.logout()
    await router.replace({ name: 'login' })
  }

  return { logout }
}
