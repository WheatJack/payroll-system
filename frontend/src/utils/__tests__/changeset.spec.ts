import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, test } from 'vitest'
import { buildChangeset } from '../changeset'
import { useGridStore } from '@/stores/grid'

const CHANGESET_FIXTURE = JSON.parse(
  readFileSync(resolve(process.cwd(), 'src/api/mock/fixtures/changeset-request.json'), 'utf-8'),
)
const CHANGESET_FROZEN_EXAMPLE = JSON.parse(
  readFileSync(resolve(process.cwd(), '../docs/api/examples/changeset-request.json'), 'utf-8'),
)

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
    expect(buildChangeset({}, { PERF_BONUS: '2000.00' }, 0, '101')).toEqual(CHANGESET_FIXTURE)
  })

  test('前端 fixture 与 docs/api/examples 的冻结示例保持一致（防漂移）', () => {
    // 仅测试期读取 docs（生产/开发构建不依赖 frontend 目录之外的文件）
    expect(CHANGESET_FIXTURE).toEqual(CHANGESET_FROZEN_EXAMPLE)
  })
})

describe('grid store 按列精度舍入（P0：不能一律截断到 2 位）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function precisionGrid(scale: number) {
    return {
      period: { id: '3', periodYm: '2026-11', status: 'EDITING' as const },
      columns: [
        {
          itemCode: 'PRECISE',
          itemName: '高精度科目',
          category: 'VARIABLE' as const,
          direction: 1 as const,
          scale,
          editable: true,
        },
      ],
      rows: [
        {
          recordId: '201',
          version: 0,
          employeeId: '1',
          empNo: 'E001',
          empName: '张三',
          deptId: '1',
          deptName: '北京研发部',
          employeeType: 'REGULAR' as const,
          jobLevel: 'P6',
          seniorityYears: '6.00',
          grossAmount: '0.00',
          deductionAmount: '0.00',
          netAmount: '0.00',
          items: { PRECISE: '0.0000' },
        },
      ],
      total: 1,
    }
  }

  test('scale=4 的列保留 1.2345，提交 changeset 也不截断', () => {
    const store = useGridStore()
    store.load(precisionGrid(4))
    store.edit('201', 'PRECISE', '1.2345')
    expect(store.valueOf('201', 'PRECISE')).toBe('1.2345')
    expect(store.buildRequest().changes[0].items[0].amount).toBe('1.2345')
  })

  test('scale=3 的列把 1.2345 按 HALF_UP 舍入为 1.235（而不是 1.23）', () => {
    const store = useGridStore()
    store.load(precisionGrid(3))
    store.edit('201', 'PRECISE', '1.2345')
    expect(store.valueOf('201', 'PRECISE')).toBe('1.235')
  })

  test('scale=2 的列仍把 1.2345 舍入为 1.23', () => {
    const store = useGridStore()
    store.load(precisionGrid(2))
    store.edit('201', 'PRECISE', '1.2345')
    expect(store.valueOf('201', 'PRECISE')).toBe('1.23')
  })

  test('显式传入 scale 时以传入值为准', () => {
    const store = useGridStore()
    store.load(precisionGrid(2))
    store.edit('201', 'PRECISE', '1.2345', 4)
    expect(store.valueOf('201', 'PRECISE')).toBe('1.2345')
  })

  test('未加载列时退回 2 位，不抛错', () => {
    const store = useGridStore()
    store.edit('999', 'UNKNOWN', '1.2345')
    expect(store.valueOf('999', 'UNKNOWN')).toBe('1.23')
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

  test('按职级筛选只影响可见行，且职级选项来自数据', () => {
    const store = useGridStore()
    const baseRow = {
      version: 0,
      employeeId: '1',
      empName: '张三',
      deptId: '1',
      deptName: '北京研发部',
      employeeType: 'REGULAR' as const,
      seniorityYears: '6.00',
      grossAmount: '0.00',
      deductionAmount: '0.00',
      netAmount: '0.00',
      items: {},
    }
    store.load({
      period: { id: '3', periodYm: '2026-11', status: 'EDITING' },
      columns: [],
      rows: [
        { ...baseRow, recordId: '101', empNo: 'E001', jobLevel: 'P6' },
        { ...baseRow, recordId: '102', empNo: 'E002', jobLevel: 'P5' },
      ],
      total: 2,
    })
    expect(store.jobLevelOptions).toEqual(['P5', 'P6'])

    store.setFilter({ jobLevel: 'P6' })
    expect(store.visibleRows.map((row) => row.empNo)).toEqual(['E001'])

    store.clearFilter()
    expect(store.visibleRows).toHaveLength(2)
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
