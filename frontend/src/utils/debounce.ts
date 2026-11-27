/** 简单防抖，带 cancel/flush（用于合计重算与关键词筛选）。 */
export interface Debounced<A extends unknown[]> {
  (...args: A): void
  cancel(): void
  flush(): void
}

export function debounce<A extends unknown[]>(fn: (...args: A) => void, waitMs = 150): Debounced<A> {
  let timer: number | undefined
  let pendingArgs: A | null = null

  const invoke = (): void => {
    timer = undefined
    const args = pendingArgs
    pendingArgs = null
    if (args) fn(...args)
  }

  const debounced = ((...args: A) => {
    pendingArgs = args
    if (timer !== undefined) window.clearTimeout(timer)
    timer = window.setTimeout(invoke, waitMs)
  }) as Debounced<A>

  debounced.cancel = () => {
    if (timer !== undefined) {
      window.clearTimeout(timer)
      timer = undefined
    }
    pendingArgs = null
  }

  debounced.flush = () => {
    if (timer !== undefined) {
      window.clearTimeout(timer)
      invoke()
    }
  }

  return debounced
}
