import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import VXETable from 'vxe-table'
import 'vxe-table/lib/style.css'
import 'vxe-pc-ui/lib/style.css'
import App from './App.vue'
import router from './router'
import { registerUnauthorizedHandler, clearToken } from './api/client'
import { useAuthStore } from './stores/auth'
import './styles/global.css'

async function bootstrap() {
  // MSW 只在显式开启时加载：VITE_USE_MOCK=true npm run dev
  if (import.meta.env.VITE_USE_MOCK === 'true') {
    const { worker } = await import('./api/mock/browser')
    await worker.start({
      onUnhandledRequest: 'bypass',
      serviceWorker: { url: '/mockServiceWorker.js' },
    })
    // eslint-disable-next-line no-console
    console.info('[mock] MSW 已启用，接口响应来自 docs/api/examples 与冻结契约')
  }

  const app = createApp(App)
  const pinia = createPinia()

  app.use(pinia)
  app.use(router)
  app.use(ElementPlus, { locale: zhCn })
  app.use(VXETable)

  registerUnauthorizedHandler(() => {
    clearToken()
    useAuthStore(pinia).logout()
    if (router.currentRoute.value.name !== 'login') {
      void router.replace({ name: 'login' })
    }
  })

  app.mount('#app')
}

void bootstrap()
