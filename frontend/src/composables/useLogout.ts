import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useGridStore } from '@/stores/grid'

/**
 * 退出登录：清 token 并**跳转登录页**。
 * 只清 token 不导航会把用户留在原页面（页面数据还在，看起来像没退出）。
 */
export function useLogout() {
  const router = useRouter()
  const auth = useAuthStore()

  async function logout(): Promise<void> {
    auth.logout()
    // 业务 store 也必须清：否则换账号后会看到上一个人的工资行与未保存修改（C-1）
    useGridStore().reset()
    await router.replace({ name: 'login' })
  }

  return { logout }
}
