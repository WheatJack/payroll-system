<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const menus = computed(() =>
  [
    { path: '/employees', title: '人员管理', perm: 'payroll:record:view', icon: 'User' },
    { path: '/items', title: '工资项', perm: 'payroll:record:view', icon: 'List' },
    { path: '/periods', title: '工资期间', perm: 'payroll:record:view', icon: 'Calendar' },
  ].filter((menu) => auth.has(menu.perm)),
)

const activeMenu = computed(() => {
  if (route.path.startsWith('/periods')) return '/periods'
  return route.path
})

async function logout() {
  try {
    await ElMessageBox.confirm('退出后需要重新登录，确认退出？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="app-shell">
    <el-aside width="208px" class="app-aside">
      <div class="app-logo">工资核算系统</div>
      <el-menu :default-active="activeMenu" router :collapse="false" background-color="#1f2d3d" text-color="#c8d0dc" active-text-color="#ffffff">
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          <span>{{ menu.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="app-header">
        <div class="breadcrumb">
          <span class="crumb-current">{{ route.meta.title ?? '' }}</span>
        </div>
        <div class="user-area">
          <el-tag size="small" type="info">{{ auth.user?.dataScope ?? '' }}</el-tag>
          <span class="user-name">{{ auth.realName || '未登录' }}</span>
          <el-button link type="primary" @click="logout">退出登录</el-button>
        </div>
      </el-header>

      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-shell {
  height: 100vh;
}
.app-aside {
  background: #1f2d3d;
  display: flex;
  flex-direction: column;
}
.app-logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.app-aside :deep(.el-menu) {
  border-right: none;
}
.app-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}
.crumb-current {
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
}
.user-area {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-name {
  font-size: 14px;
  color: #303133;
}
.app-main {
  background: #f5f7fa;
  padding: 16px;
  overflow: auto;
}
</style>
