/**
 * 二进制下载（报表导出 / 导入模板 / 错误报告）。
 *
 * 契约要点（v1.1）：
 * - 成功：`200` + xlsx 二进制流，文件名在 `Content-Disposition`
 * - 失败：`4xx/5xx` + **JSON 统一响应包**（`Content-Type: application/json`）
 * 因此必须先拿原始 response，再按 `Content-Type` 分流，不能只看状态码。
 */
import type { AxiosRequestConfig } from 'axios'
import { buildApiError, isApiError, notifyUnauthorized, rawHttp } from './client'
import type { ApiError } from './client'

export interface DownloadedFile {
  blob: Blob
  filename: string
}

/** 从 `Content-Disposition` 解析文件名，支持 RFC 5987 的 `filename*=UTF-8''...`。 */
export function parseContentDisposition(header?: string | null): string | null {
  if (!header) return null
  const extended = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header)
  if (extended?.[1]) {
    return decodeURIComponent(extended[1].trim().replace(/^"|"$/g, ''))
  }
  const quoted = /filename\s*=\s*"([^"]+)"/i.exec(header)
  if (quoted?.[1]) return quoted[1].trim()
  const plain = /filename\s*=\s*([^;]+)/i.exec(header)
  return plain?.[1]?.trim() ?? null
}

export function isJsonContentType(contentType?: string | null): boolean {
  if (!contentType) return false
  return /(^|[/+])json\b/i.test(contentType)
}

/** 读 Blob 文本；jsdom 等环境没有 `Blob.text()`，用 FileReader 兜底。 */
export async function blobToText(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') {
    return blob.text()
  }
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

/** 把失败响应（可能是 Blob 包着的 JSON）转成可读的 ApiError。 */
export async function errorFromBlobResponse(data: unknown, status: number): Promise<ApiError> {
  if (data instanceof Blob && isJsonContentType(data.type)) {
    try {
      const text = await blobToText(data)
      const body = JSON.parse(text) as { code?: unknown; msg?: unknown; data?: unknown }
      return buildApiError(
        typeof body.code === 'number' ? body.code : status,
        typeof body.msg === 'string' ? body.msg : undefined,
        body.data,
        status,
      )
    } catch {
      // 落空时退回状态码文案
    }
  }
  return buildApiError(status, undefined, null, status)
}

/**
 * 请求一个二进制端点。
 * - `200` + 非 JSON → 返回 blob 与文件名
 * - `200` + JSON（后端异常兜底）→ 抛 ApiError
 * - 非 2xx → 解析 JSON 错误包并抛 ApiError；401 同时触发全局登出
 */
export async function downloadFile(config: AxiosRequestConfig, fallbackFilename = 'download.xlsx'): Promise<DownloadedFile> {
  try {
    const response = await rawHttp.request<Blob>({ ...config, responseType: 'blob' })
    const contentType = response.headers['content-type'] as string | undefined
    if (isJsonContentType(contentType)) {
      throw await errorFromBlobResponse(response.data, response.status)
    }
    const filename =
      parseContentDisposition(response.headers['content-disposition'] as string | undefined) ?? fallbackFilename
    return { blob: response.data, filename }
  } catch (error) {
    // 自己抛出的 ApiError（200 但 Content-Type 是 JSON）直接透传
    if (isApiError(error)) {
      if (error.code === 401) notifyUnauthorized()
      throw error
    }
    const response = (error as { response?: { status: number; data: unknown } }).response
    if (response) {
      const apiError = await errorFromBlobResponse(response.data, response.status)
      if (apiError.code === 401) notifyUnauthorized()
      throw apiError
    }
    throw buildApiError(-1, '下载失败，请稍后重试', null)
  }
}

/** 触发浏览器下载，并确保 objectURL 被回收。 */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // 立即 revoke 在部分浏览器会打断下载，延迟回收
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
