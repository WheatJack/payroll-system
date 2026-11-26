/**
 * changeset 构建：只把**真正改动**的单元格放进请求。
 *
 * 契约（PATCH /periods/{id}/records）：
 * `{ changes: [{ recordId, version, items: [{ itemCode, amount }] }] }`
 * - `version` 必须是该行 grid 响应里的原始版本号（乐观锁）
 * - 金额是字符串，比较用 money.eq，避免 "100.0" 与 "100.00" 造成假改动
 */
import type { ChangesetItem, ChangesetRequest } from '@/api/models'
import { eq } from './money'

export function buildChangeset(
  original: Readonly<Record<string, string>>,
  current: Readonly<Record<string, string>>,
  version = 0,
  recordId = '',
): ChangesetRequest {
  const codes = new Set([...Object.keys(original), ...Object.keys(current)])
  const items: ChangesetItem[] = []

  for (const itemCode of codes) {
    const before = original[itemCode] ?? '0.00'
    const after = current[itemCode] ?? '0.00'
    if (before === after) continue
    try {
      if (eq(before, after)) continue
    } catch {
      // 任一侧不是合法金额时按字符串差异处理，交给后端校验并返回 400
    }
    items.push({ itemCode, amount: after })
  }

  if (items.length === 0) {
    return { changes: [] }
  }
  return { changes: [{ recordId, version, items }] }
}
