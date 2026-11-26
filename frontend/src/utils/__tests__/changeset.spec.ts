import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, test } from 'vitest'
import { buildChangeset } from '../changeset'
import { useGridStore } from '@/stores/grid'

describe('buildChangeset 只提交改动', () => {
  test('未修改的单元格不进入 changeset', () => {
    expect(buildChangeset({ a: '100.00' }, { a: '100.00' }).changes).toHaveLength(0)
  })

  test('修改后的单元格带上原始 version', () => {
    const r = buildChangeset({ a: '100.00' }, { a: '120.00' }, 7)
    expect(r.changes[0].version).toBe(7)
    expect(r.changes[0].items[0].amount).toBe('120.00')
  })

  test('数值相等但写法不同（100.0 / 100.00）不算修改', () => {
    expect(buildChangeset({ a: '100.00' }, { a: '100.0' }).changes).toHaveLength(0)
  })

  test('新增科目与多科目改动都进入同一条 change', () => {
    const r = buildChangeset({ a: '100.00' }, { a: '120.00', b: '30.00' }, 3, '101')
    expect(r.changes).toHaveLength(1)
    expect(r.changes[0].recordId).toBe('101')
    expect(r.changes[0].items.map((item) => item.itemCode).sort()).toEqual(['a', 'b'])
  })

  test('结构与冻结契约示例 changeset-request.json 完全一致', () => {
    const example = JSON.parse(
      readFileSync(resolve(process.cwd(), '../docs/api/examples/changeset-request.json'), 'utf-8'),
    )
    const built = buildChangeset({}, { PERF_BONUS: '2000.00' }, 0, '101')
    expect(built).toEqual(example)
  })
})

describe('grid store 的 dirty 与筛选解耦', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  test('未保存的编辑在切换筛选后仍然存在', () => {
    const store = useGridStore()
    store.edit('101', 'PERF_BONUS', '2000.00')
    store.setFilter({ deptId: '2' })
    store.clearFilter()
    expect(store.valueOf('101', 'PERF_BONUS')).toBe('2000.00')
  })

  test('筛选只影响可见行，不影响 dirty 与 changeset', () => {
    const store = useGridStore()
    store.load({
      period: { id: '3', periodYm: '2026-11', status: 'EDITING' },
      columns: [
        { itemCode: 'PERF_BONUS', itemName: '绩效奖金', category: 'VARIABLE', direction: 1, scale: 2, editable: true },
      ],
      rows: [
        {
          recordId: '101',
          version: 2,
          employeeId: '1',
          empNo: 'E001',
          empName: '张三',
          deptId: '1',
          deptName: '北京研发部',
          employeeType: 'REGULAR',
          jobLevel: 'P6',
          seniorityYears: '6.00',
          grossAmount: '8600.00',
          deductionAmount: '0.00',
          netAmount: '8600.00',
          items: { PERF_BONUS: '1000.00' },
        },
      ],
      total: 1,
    })
    store.edit('101', 'PERF_BONUS', '2000.00')
    store.setFilter({ deptId: '999' })
    expect(store.visibleRows).toHaveLength(0)
    expect(store.valueOf('101', 'PERF_BONUS')).toBe('2000.00')
    const request = store.buildRequest()
    expect(request.changes).toHaveLength(1)
    expect(request.changes[0].version).toBe(2)
    expect(request.changes[0].items[0].amount).toBe('2000.00')
  })

  test('applySaveResult 用服务端返回行刷新版本号并清掉已保存的 dirty', () => {
    const store = useGridStore()
    store.load({
      period: { id: '3', periodYm: '2026-11', status: 'EDITING' },
      columns: [
        { itemCode: 'PERF_BONUS', itemName: '绩效奖金', category: 'VARIABLE', direction: 1, scale: 2, editable: true },
      ],
      rows: [
        {
          recordId: '101',
          version: 0,
          employeeId: '1',
          empNo: 'E001',
          empName: '张三',
          deptId: '1',
          deptName: '北京研发部',
          employeeType: 'REGULAR',
          jobLevel: 'P6',
          seniorityYears: '6.00',
          grossAmount: '8600.00',
          deductionAmount: '0.00',
          netAmount: '8600.00',
          items: { PERF_BONUS: '1000.00' },
        },
      ],
      total: 1,
    })
    store.edit('101', 'PERF_BONUS', '2000.00')
    store.applySaveResult({
      updated: 1,
      rows: [
        {
          recordId: '101',
          version: 1,
          employeeId: '1',
          empNo: 'E001',
          empName: '张三',
          deptId: '1',
          deptName: '北京研发部',
          employeeType: 'REGULAR',
          jobLevel: 'P6',
          seniorityYears: '6.00',
          grossAmount: '9600.00',
          deductionAmount: '0.00',
          netAmount: '9600.00',
          items: { PERF_BONUS: '2000.00' },
        },
      ],
    })
    expect(store.valueOf('101', 'PERF_BONUS')).toBe('2000.00')
    expect(store.isDirty('101', 'PERF_BONUS')).toBe(false)
    expect(store.versionOf('101')).toBe(1)
    expect(store.dirtyCount).toBe(0)
  })
})
