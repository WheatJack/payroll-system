<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useLogout } from '@/composables/useLogout'
import { resolveLandingPath } from '@/utils/landing'

const router = useRouter()
const auth = useAuthStore()
const { logout: performLogout } = useLogout()
const landing = computed(() => resolveLandingPath(auth.permissions))

function goHome() {
  void router.replace(landing.value ?? '/no-access')
}

function logout() {
  void performLogout()
}
</script>

<template>
  <div class="feedback-page">
    <el-result icon="warning" title="403" sub-title="当前账号没有访问该页面的权限，请联系系统管理员。" />
    <div class="actions">
      <el-button type="primary" @click="goHome">
        {{ landing ? '回到我的首页' : '查看账号状态' }}
      </el-button>
      <el-button @click="logout">退出登录</el-button>
    </div>
  </div>
</template>

<style scoped>
.feedback-page {
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.actions {
  display: flex;
  gap: 8px;
}
</style>
