import { describe, expect, test } from 'vitest'
import { errorFromBlobResponse, isJsonContentType, parseContentDisposition } from '../download'

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
    expect(isJsonContentType('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')).toBe(false)
    expect(isJsonContentType(undefined)).toBe(false)
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
