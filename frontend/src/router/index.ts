import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { resolveLandingPath } from '@/utils/landing'

/**
 * 权限点（详细设计 §4.7）：
 * payroll:record:view / record:edit / period:lock / period:unlock /
 * import:submit / export / 系统管理 sys:*
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
    // 不做静态 redirect：落地页由守卫按权限动态选择（SYS_ADMIN 没有 payroll:record:view）
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
      {
        path: 'periods/:id/import',
        name: 'period-import',
        component: () => import('@/views/period/ImportWizard.vue'),
        meta: { title: '导入工资数据', perm: 'payroll:import:submit' },
      },
      {
        path: 'reports/monthly',
        name: 'report-monthly',
        component: () => import('@/views/report/MonthlyReportView.vue'),
        meta: { title: '月度报表', perm: 'payroll:record:view' },
      },
      {
        path: 'reports/annual',
        name: 'report-annual',
        component: () => import('@/views/report/AnnualReportView.vue'),
        meta: { title: '年度报表', perm: 'payroll:record:view' },
      },
      {
        path: 'system/users',
        name: 'system-users',
        component: () => import('@/views/system/UserListView.vue'),
        meta: { title: '用户管理', perm: 'sys:user:view' },
      },
      {
        path: 'system/roles',
        name: 'system-roles',
        component: () => import('@/views/system/RoleListView.vue'),
        meta: { title: '角色与授权', perm: 'sys:role:view' },
      },
      {
        path: 'system/depts',
        name: 'system-depts',
        component: () => import('@/views/system/DeptTreeView.vue'),
        meta: { title: '部门管理', perm: 'sys:dept:edit' },
      },
      {
        path: 'system/companies',
        name: 'system-companies',
        component: () => import('@/views/system/CompanyListView.vue'),
        meta: { title: '法人主体', perm: 'sys:company:edit' },
      },
      {
        path: 'system/config',
        name: 'system-config',
        component: () => import('@/views/system/ConfigView.vue'),
        meta: { title: '系统参数', perm: 'sys:config:view' },
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
    path: '/no-access',
    name: 'no-access',
    component: () => import('@/views/NoAccessView.vue'),
    meta: { title: '无可用页面' },
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

  // 根路径没有具体子路由：按当前账号权限挑落地页；
  // 一个页面都进不去时给「无可用页面」兜底页，不能落到 403 形成死循环
  if (to.path === '/') {
    const landing = resolveLandingPath(auth.permissions)
    return landing ? { path: landing, replace: true } : { name: 'no-access', replace: true }
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
