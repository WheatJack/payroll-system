<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useLogout } from '@/composables/useLogout'

const auth = useAuthStore()
const route = useRoute()
const { logout: performLogout } = useLogout()

interface MenuEntry {
  path: string
  title: string
  perm?: string
  children?: MenuEntry[]
}

const menus = computed<MenuEntry[]>(() => {
  const entries: MenuEntry[] = [
    { path: '/employees', title: '人员管理', perm: 'payroll:record:view' },
    { path: '/items', title: '工资项', perm: 'payroll:record:view' },
    { path: '/periods', title: '工资期间', perm: 'payroll:record:view' },
    { path: '/reports/monthly', title: '月度报表', perm: 'payroll:record:view' },
    { path: '/reports/annual', title: '年度报表', perm: 'payroll:record:view' },
  ].filter((menu) => auth.has(menu.perm!))

  // 系统管理：无任何 sys:* 权限时整组不渲染（仅显隐，真正边界在后端）
  const systemChildren = [
    { path: '/system/users', title: '用户管理', perm: 'sys:user:view' },
    { path: '/system/roles', title: '角色与授权', perm: 'sys:role:view' },
    { path: '/system/depts', title: '部门管理', perm: 'sys:dept:edit' },
    { path: '/system/companies', title: '法人主体', perm: 'sys:company:edit' },
    { path: '/system/config', title: '系统参数', perm: 'sys:config:view' },
  ].filter((menu) => auth.has(menu.perm))

  if (systemChildren.length > 0) {
    entries.push({ path: 'system', title: '系统管理', children: systemChildren })
  }
  return entries
})

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
  // 清 token + 跳转登录页（统一走 useLogout，避免只清不清导航）
  await performLogout()
}
</script>

<template>
  <el-container class="app-shell">
    <el-aside width="208px" class="app-aside">
      <div class="app-logo">工资核算系统</div>
      <el-menu :default-active="activeMenu" router :collapse="false" background-color="#1f2d3d" text-color="#c8d0dc" active-text-color="#ffffff">
        <template v-for="menu in menus" :key="menu.path">
          <el-sub-menu v-if="menu.children" :index="menu.path">
            <template #title>
              <span>{{ menu.title }}</span>
            </template>
            <el-menu-item v-for="child in menu.children" :key="child.path" :index="child.path">
              <span>{{ child.title }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="menu.path">
            <span>{{ menu.title }}</span>
          </el-menu-item>
        </template>
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
