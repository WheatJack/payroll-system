import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

/** 仅在 `VITE_USE_MOCK=true` 时由 main.ts 动态加载 */
export const worker = setupWorker(...handlers)
