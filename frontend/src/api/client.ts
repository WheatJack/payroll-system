/**
 * 统一 HTTP 客户端。
 *
 * 契约（docs/api/openapi.yaml）：
 * - 统一响应包 `{ code, msg, data }`，`code = 0` 表示成功；本客户端对调用方**只返回 data**。
 * - 失败时抛出 `ApiError`，保留 `code` / `msg` / `data`（409 changeset 冲突的 `data.conflicts` 要用）。
 * - 401（HTTP 或业务码）触发全局登出回调，URL 上不拼接 token。
 * - 金额、ID 全为字符串，这里不做任何转换，避免精度损失。
 */
import axios, { type AxiosRequestConfig } from 'axios'

export const TOKEN_KEY = 'payroll.token'

export function getToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) ?? ''
  } catch {
    return ''
  }
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export interface ApiError extends Error {
  /** 契约中的业务码：400/401/403/409/500；网络错误为 -1 */
  code: number
  /** 错误响应里的 data（如 changeset 冲突的 conflicts 列表） */
  data: unknown
  httpStatus?: number
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof Error && typeof (error as ApiError).code === 'number'
}

function createApiError(code: number, message: string, data: unknown, httpStatus?: number): ApiError {
  const error = new Error(message) as ApiError
  error.name = 'ApiError'
  error.code = code
  error.data = data
  error.httpStatus = httpStatus
  return error
}

let unauthorizedHandler: (() => void) | null = null

/** 由 main.ts 注册：清空登录态并跳转登录页。 */
export function registerUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

function handleUnauthorized(): void {
  clearToken()
  unauthorizedHandler?.()
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/v1',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && typeof (body as { code?: unknown }).code === 'number') {
      const envelope = body as { code: number; msg?: string; data?: unknown }
      if (envelope.code === 0) {
        return envelope.data
      }
      if (envelope.code === 401) {
        handleUnauthorized()
      }
      throw createApiError(envelope.code, envelope.msg || `请求失败（${envelope.code}）`, envelope.data, response.status)
    }
    return body
  },
  (error) => {
    const status: number | undefined = error.response?.status
    const body = error.response?.data as { code?: unknown; msg?: unknown; data?: unknown } | undefined
    const code = typeof body?.code === 'number' ? body.code : (status ?? -1)
    const message =
      (typeof body?.msg === 'string' && body.msg) ||
      (status === 401 ? '未认证或登录已过期' : error.message || '网络异常')
    if (code === 401) {
      handleUnauthorized()
    }
    throw createApiError(code, message, body?.data, status)
  },
)

// 拦截器已把响应解包为 data，这里用泛型把真实返回值告知调用方
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  return (await http.request(config)) as unknown as T
}

export const api = {
  get: <T>(url: string, config?: AxiosRequestConfig) => request<T>({ ...config, url, method: 'GET' }),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, url, method: 'POST', data }),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, url, method: 'PUT', data }),
  patch: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, url, method: 'PATCH', data }),
  delete: <T>(url: string, config?: AxiosRequestConfig) => request<T>({ ...config, url, method: 'DELETE' }),
}
