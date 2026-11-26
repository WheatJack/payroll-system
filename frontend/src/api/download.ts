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
    const raw = extended[1].trim().replace(/^"|"$/g, '')
    try {
      return decodeURIComponent(raw)
    } catch {
      // 未按百分号编码的中文会让 decodeURIComponent 抛错，退回原始值而不是整次下载失败
      return raw
    }
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

export function isHtmlContentType(contentType?: string | null): boolean {
  if (!contentType) return false
  return /text\/html|application\/xhtml\+xml/i.test(contentType)
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

/** 读 Blob 前若干字节；同样兼容没有 `arrayBuffer()` 的环境。 */
export async function readBlobHead(blob: Blob, bytes = 4): Promise<Uint8Array | null> {
  const head = blob.slice(0, bytes)
  if (typeof head.arrayBuffer === 'function') {
    try {
      return new Uint8Array(await head.arrayBuffer())
    } catch {
      return null
    }
  }
  return new Promise<Uint8Array | null>((resolve) => {
    const reader = new FileReader()
    reader.onload = () =>
      resolve(reader.result instanceof ArrayBuffer ? new Uint8Array(reader.result) : null)
    reader.onerror = () => resolve(null)
    reader.readAsArrayBuffer(head)
  })
}

/**
 * 校验下载内容确实是 xlsx（ZIP 头 `PK`）。
 * 200 + HTML 通常是网关错误页/登录页被当成文件存盘，必须在保存前拦住。
 */
export async function assertExcelBlob(blob: Blob, contentType?: string | null): Promise<void> {
  if (isHtmlContentType(contentType)) {
    throw buildApiError(500, '服务端返回了网页而不是文件（可能被网关或登录页拦截）', null)
  }
  const head = await readBlobHead(blob, 2)
  if (!head || head.length < 2 || head[0] !== 0x50 || head[1] !== 0x4b) {
    throw buildApiError(500, '下载内容不是有效的 xlsx（缺少 PK 文件头）', null)
  }
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

export interface DownloadOptions {
  /** 是否校验 xlsx 文件头，默认 true（本系统所有下载都是 xlsx） */
  expectExcel?: boolean
  fallbackFilename?: string
}

/**
 * 请求一个二进制端点。
 * - `200` + 非 JSON → 校验文件头、返回 blob 与文件名
 * - `200` + JSON（后端异常兜底）→ 抛 ApiError
 * - 非 2xx → 解析 JSON 错误包并抛 ApiError；401 同时触发全局登出
 */
export async function downloadFile(
  config: AxiosRequestConfig,
  fallbackFilenameOrOptions: string | DownloadOptions = 'download.xlsx',
): Promise<DownloadedFile> {
  const options: DownloadOptions =
    typeof fallbackFilenameOrOptions === 'string'
      ? { fallbackFilename: fallbackFilenameOrOptions }
      : fallbackFilenameOrOptions
  const fallbackFilename = options.fallbackFilename ?? 'download.xlsx'
  const expectExcel = options.expectExcel ?? true

  try {
    const response = await rawHttp.request<Blob>({ ...config, responseType: 'blob' })
    const contentType = response.headers['content-type'] as string | undefined
    if (isJsonContentType(contentType)) {
      throw await errorFromBlobResponse(response.data, response.status)
    }

    const disposition = response.headers['content-disposition'] as string | undefined
    if (!disposition) {
      // 浏览器只能看到被 CORS 暴露的响应头；跨域部署时这是最常见的「文件名丢失」原因
      console.warn(
        '[download] 响应缺少 Content-Disposition，使用回退文件名；跨域部署需在网关设置 Access-Control-Expose-Headers: Content-Disposition',
      )
    }
    const filename = parseContentDisposition(disposition) ?? fallbackFilename

    if (expectExcel) {
      await assertExcelBlob(response.data, contentType)
    }
    return { blob: response.data, filename }
  } catch (error) {
    // 自己抛出的 ApiError（200 但 Content-Type 是 JSON / 文件头不对）直接透传
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
