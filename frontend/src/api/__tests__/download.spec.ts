import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { rawHttp, registerUnauthorizedHandler } from '../client'
import {
  assertExcelBlob,
  downloadFile,
  errorFromBlobResponse,
  isHtmlContentType,
  isJsonContentType,
  parseContentDisposition,
  saveBlob,
} from '../download'

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

function xlsxBlob(content = 'PK\u0003\u0004mock'): Blob {
  return new Blob([content], { type: XLSX })
}

describe('Content-Disposition 文件名解析', () => {
  test('普通引号形式', () => {
    expect(parseContentDisposition('attachment; filename="monthly-2026-11-20261126T101500.xlsx"')).toBe(
      'monthly-2026-11-20261126T101500.xlsx',
    )
  })

  test('RFC 5987 filename* 形式（含中文）', () => {
    expect(parseContentDisposition("attachment; filename*=UTF-8''%E5%B7%A5%E8%B5%84%E8%A1%A8-2026-11.xlsx")).toBe(
      '工资表-2026-11.xlsx',
    )
  })

  test('未百分号编码的 filename* 不抛错，退回原始值', () => {
    expect(parseContentDisposition("attachment; filename*=UTF-8''工资表.xlsx")).toBe('工资表.xlsx')
    expect(parseContentDisposition("attachment; filename*=UTF-8''%E4%B8")).toBe('%E4%B8')
  })

  test('无引号形式与缺失头', () => {
    expect(parseContentDisposition('attachment; filename=payslip.xlsx')).toBe('payslip.xlsx')
    expect(parseContentDisposition(null)).toBeNull()
    expect(parseContentDisposition('attachment')).toBeNull()
  })
})

describe('Content-Type 判定', () => {
  test('识别 json（含 charset 与 problem+json）', () => {
    expect(isJsonContentType('application/json')).toBe(true)
    expect(isJsonContentType('application/json;charset=UTF-8')).toBe(true)
    expect(isJsonContentType('application/problem+json')).toBe(true)
  })

  test('xlsx / 空值不算 json', () => {
    expect(isJsonContentType(XLSX)).toBe(false)
    expect(isJsonContentType(undefined)).toBe(false)
  })

  test('识别 html（网关错误页）', () => {
    expect(isHtmlContentType('text/html; charset=utf-8')).toBe(true)
    expect(isHtmlContentType(XLSX)).toBe(false)
  })
})

describe('失败响应（JSON 藏在 Blob 里）转 ApiError', () => {
  test('从 blob 中解析出后端 msg 与 code', async () => {
    const blob = new Blob([JSON.stringify({ code: 400, msg: '缺少 params.periodYm', data: null })], {
      type: 'application/json',
    })
    const error = await errorFromBlobResponse(blob, 400)
    expect(error.code).toBe(400)
    expect(error.message).toBe('缺少 params.periodYm')
    expect(error.httpStatus).toBe(400)
  })

  test('非 JSON blob 退回状态码默认文案', async () => {
    const blob = new Blob(['not json'], { type: 'text/plain' })
    const error = await errorFromBlobResponse(blob, 403)
    expect(error.code).toBe(403)
    expect(error.message).toBe('无权限执行该操作')
  })
})

describe('xlsx 文件头校验（200 + HTML 不能被当文件存盘）', () => {
  test('PK 开头通过', async () => {
    await expect(assertExcelBlob(xlsxBlob(), XLSX)).resolves.toBeUndefined()
  })

  test('HTML 内容类型直接拒绝', async () => {
    await expect(assertExcelBlob(new Blob(['<html></html>'], { type: 'text/html' }), 'text/html')).rejects.toThrow(
      '服务端返回了网页而不是文件',
    )
  })

  test('缺少 PK 文件头拒绝（伪装成 xlsx 的错误页）', async () => {
    const fake = new Blob(['<html>error</html>'], { type: XLSX })
    await expect(assertExcelBlob(fake, XLSX)).rejects.toThrow('缺少 PK 文件头')
  })
})

describe('downloadFile 行为', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  test('成功：返回 blob 与 Content-Disposition 中的文件名', async () => {
    vi.spyOn(rawHttp, 'request').mockResolvedValue({
      status: 200,
      headers: { 'content-type': XLSX, 'content-disposition': 'attachment; filename="a.xlsx"' },
      data: xlsxBlob(),
    } as never)

    const file = await downloadFile({ url: '/reports/export', method: 'POST' })
    expect(file.filename).toBe('a.xlsx')
    expect(file.blob.type).toBe(XLSX)
  })

  test('缺 Content-Disposition 时退回 fallback，并给出跨域提示', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    vi.spyOn(rawHttp, 'request').mockResolvedValue({ status: 200, headers: { 'content-type': XLSX }, data: xlsxBlob() } as never)

    const file = await downloadFile({ url: '/x' }, 'fallback.xlsx')
    expect(file.filename).toBe('fallback.xlsx')
    expect(warn.mock.calls.flat().join(' ')).toContain('Access-Control-Expose-Headers')
  })

  test('401：抛 ApiError 并触发全局登出', async () => {
    const logout = vi.fn()
    registerUnauthorizedHandler(logout)
    vi.spyOn(rawHttp, 'request').mockRejectedValue({
      response: { status: 401, data: new Blob([JSON.stringify({ code: 401, msg: '未认证或登录已过期' })], { type: 'application/json' }) },
    })

    await expect(downloadFile({ url: '/x' })).rejects.toMatchObject({ code: 401 })
    expect(logout).toHaveBeenCalledTimes(1)
  })

  test('200 + JSON 统一响应包（后端异常兜底）按错误处理', async () => {
    vi.spyOn(rawHttp, 'request').mockResolvedValue({
      status: 200,
      headers: { 'content-type': 'application/json' },
      data: new Blob([JSON.stringify({ code: 400, msg: '参数校验失败' })], { type: 'application/json' }),
    } as never)

    await expect(downloadFile({ url: '/x' })).rejects.toMatchObject({ code: 400, message: '参数校验失败' })
  })
})

describe('saveBlob 的 objectURL 时序', () => {
  let revoke: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(URL, 'createObjectURL', { value: vi.fn(() => 'blob:mock-url'), configurable: true })
    revoke = vi.fn()
    Object.defineProperty(URL, 'revokeObjectURL', { value: revoke, configurable: true })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  test('点击后不立即 revoke，1s 后回收，避免打断下载', () => {
    saveBlob(new Blob(['PK']), 'x.xlsx')
    expect(revoke).not.toHaveBeenCalled()
    vi.advanceTimersByTime(999)
    expect(revoke).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(revoke).toHaveBeenCalledWith('blob:mock-url')
  })
})
