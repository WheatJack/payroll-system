/**
 * 「只认最新一次请求」的序号守卫。
 *
 * 用于报表查询：切维度/连点查询时，先发的旧响应可能后到，
 * 若直接覆盖 rows 会把「按人」的行渲染进「按部门」的列。
 */
export interface LatestOnly {
  /** 开一次新请求，返回它的序号 */
  next(): number
  /** 该序号是否仍是最新一次请求 */
  isLatest(token: number): boolean
  /** 让所有在途请求作废 */
  invalidate(): void
}

export function createLatestOnly(): LatestOnly {
  let sequence = 0
  return {
    next() {
      sequence += 1
      return sequence
    },
    isLatest(token: number) {
      return token === sequence
    },
    invalidate() {
      sequence += 1
    },
  }
}
