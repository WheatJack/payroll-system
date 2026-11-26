/**
 * Excel 剪贴板解析。
 *
 * Excel / WPS 复制多行多列时，剪贴板是 TSV：列用 `\t`、行用 `\r\n`（或 `\n`）。
 * 这里只做文本切分，不做任何数值转换；金额清洗交给 `money.coerce`。
 */
export function parseClipboardMatrix(text: string): string[][] {
  if (!text) return []
  const normalized = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const lines = normalized.split('\n')
  // 末尾空行（Excel 常带一个）不算数据行
  while (lines.length > 0 && lines[lines.length - 1].trim() === '') {
    lines.pop()
  }
  return lines.map((line) => line.split('\t').map((cell) => cell.trim()))
}
