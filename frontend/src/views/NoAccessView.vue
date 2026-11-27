<script setup lang="ts">
/**
 * 兜底页：账号已登录但没有任何页面权限（理论上不该发生）。
 * 这里展示当前账号与权限点，并提供「重新获取权限」和「退出登录」，
 * 避免把用户送进 403 → 首页 → 403 的死循环。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useLogout } from '@/composables/useLogout'
import { resolveLandingPath } from '@/utils/landing'

const router = useRouter()
const auth = useAuthStore()
const { logout: performLogout } = useLogout()
const refreshing = ref(false)

const permissionCount = computed(() => auth.permissions.length)
const landing = computed(() => resolveLandingPath(auth.permissions))

async function refresh() {
  refreshing.value = true
  try {
    await auth.fetchMe()
    const target = resolveLandingPath(auth.permissions)
    if (target) {
      ElMessage.success('权限已更新，正在进入系统')
      await router.replace(target)
    } else {
      ElMessage.warning('账号仍未获得任何页面权限，请联系管理员')
    }
  } catch {
    ElMessage.error('权限获取失败，请稍后重试')
  } finally {
    refreshing.value = false
  }
}

function logout() {
  void performLogout()
}
</script>

<template>
  <div class="feedback-page">
    <el-result icon="info" title="没有可访问的页面" sub-title="当前账号尚未被授予任何页面权限，请联系系统管理员。">
      <template #extra>
        <div class="account">
          <div class="row"><span class="label">账号</span>{{ auth.user?.username ?? '—' }}</div>
          <div class="row"><span class="label">姓名</span>{{ auth.user?.realName ?? '—' }}</div>
          <div class="row"><span class="label">数据范围</span>{{ auth.user?.dataScope ?? '—' }}</div>
          <div class="row"><span class="label">权限点</span>{{ permissionCount }} 项</div>
        </div>
        <div class="actions">
          <el-button type="primary" :loading="refreshing" @click="refresh">重新获取权限</el-button>
          <el-button v-if="landing" @click="router.replace(landing)">进入系统</el-button>
          <el-button @click="logout">退出登录</el-button>
        </div>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.feedback-page {
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.account {
  min-width: 280px;
  margin: 0 auto 16px;
  padding: 12px 16px;
  background: #f7f9fc;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  text-align: left;
}
.row {
  font-size: 13px;
  color: #303133;
  line-height: 24px;
}
.label {
  display: inline-block;
  width: 80px;
  color: #909399;
}
.actions {
  display: flex;
  gap: 8px;
  justify-content: center;
}
</style>
