import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 权限点（详细设计 §4.7）：
 * payroll:record:view / record:edit / period:lock / period:unlock /
 * import:submit / export / sensitive:view
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/periods',
    children: [
      {
        path: 'employees',
        name: 'employees',
        component: () => import('@/views/employee/EmployeeListView.vue'),
        meta: { title: '人员管理', perm: 'payroll:record:view' },
      },
      {
        path: 'items',
        name: 'items',
        component: () => import('@/views/item/ItemListView.vue'),
        meta: { title: '工资项', perm: 'payroll:record:view' },
      },
      {
        path: 'periods',
        name: 'periods',
        component: () => import('@/views/period/PeriodListView.vue'),
        meta: { title: '工资期间', perm: 'payroll:record:view' },
      },
      {
        path: 'periods/:id/grid',
        name: 'payroll-grid',
        component: () => import('@/views/period/PayrollGridView.vue'),
        meta: { title: '编制台', perm: 'payroll:record:view' },
      },
      // 开发专用：vxe-table PoC 只在 dev 注册，生产环境不存在该路由
      ...(import.meta.env.DEV
        ? [
            {
              path: 'poc/grid',
              name: 'payroll-grid-poc',
              component: () => import('@/views/period/PayrollGridPoC.vue'),
              meta: { title: '编制台 PoC', perm: 'payroll:record:view' },
            },
          ]
        : []),
    ],
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { public: true, title: '无权限' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.name === 'login') {
    return auth.token ? { path: '/' } : true
  }
  if (to.meta.public) {
    return true
  }
  if (!auth.token) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      auth.logout()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  const perm = to.meta.perm as string | undefined
  if (perm && !auth.has(perm)) {
    return { name: 'forbidden' }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · 工资核算系统` : '工资核算系统'
})

export default router
