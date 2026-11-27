import { onBeforeUnmount, onMounted } from 'vue'

export interface AutoRefreshOptions {
  /** 轮询间隔，默认 15s */
  intervalMs?: number
  /** 返回 false 时跳过本次刷新（例如用户正在编辑） */
  enabled?: () => boolean
}

/**
 * 页面静默自动刷新：
 * - 定时轮询（默认 15s）
 * - 窗口重新获得焦点 / 标签页重新可见时立即刷新
 *
 * 用于「别人锁定期间后，我这边的列表还显示编制中」这类协作场景。
 * 只在列表页使用——编制台有未保存编辑，不能自动刷新。
 */
export function useAutoRefresh(refresh: () => void | Promise<void>, options: AutoRefreshOptions = {}) {
  const intervalMs = options.intervalMs ?? 15_000
  let timer: number | undefined
  let running = false

  async function run(): Promise<void> {
    if (running) return
    if (options.enabled && !options.enabled()) return
    running = true
    try {
      await refresh()
    } finally {
      running = false
    }
  }

  function onFocus(): void {
    void run()
  }

  function onVisibilityChange(): void {
    if (document.visibilityState === 'visible') {
      void run()
    }
  }

  onMounted(() => {
    timer = window.setInterval(() => void run(), intervalMs)
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisibilityChange)
  })

  onBeforeUnmount(() => {
    if (timer !== undefined) {
      window.clearInterval(timer)
      timer = undefined
    }
    window.removeEventListener('focus', onFocus)
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return { refreshNow: run }
}
