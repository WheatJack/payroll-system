package com.insbean.payroll.record;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.common.Money;
import com.insbean.payroll.common.VersionConflictException;
import com.insbean.payroll.domain.entity.PayItemEntity;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.item.ItemService;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.record.dto.ChangesetChange;
import com.insbean.payroll.record.dto.ChangesetItem;
import com.insbean.payroll.record.dto.ChangesetReq;
import com.insbean.payroll.record.dto.ChangesetResult;
import com.insbean.payroll.record.dto.GridColumn;
import com.insbean.payroll.record.dto.GridPeriod;
import com.insbean.payroll.record.dto.GridResponse;
import com.insbean.payroll.record.dto.GridRow;
import com.insbean.payroll.record.mapper.PayRecordItemMapper;
import com.insbean.payroll.record.mapper.PayRecordMapper;
import com.insbean.payroll.security.DataScopeEnforcer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 编制台：全量工资表 + changeset 批量保存。
 *
 * <p>三条不变量：
 * <ul>
 *   <li>合计 = 按方向分别加总明细，绝不二次舍入；有扣款项时 gross 与 deduction 分开算；</li>
 *   <li>并发编辑用 (recordId, version) 乐观锁，任一冲突整体失败并列出全部冲突行；</li>
 *   <li>写之前必须过 {@code assertWritableForUpdate}（行锁），锁定/归档期间连一次 UPDATE 都不发。</li>
 * </ul>
 */
@Service
public class RecordService {

    private static final int MONEY_SCALE = 2;

    /** DECIMAL(18,4) 可表示的最大绝对值。 */
    private static final BigDecimal MAX_DECIMAL_18_4 = new BigDecimal("99999999999999.9999");

    private final PeriodService periodService;
    private final ItemService itemService;
    private final PayRecordMapper recordMapper;
    private final PayRecordItemMapper recordItemMapper;
    private final ColumnSnapshotFactory columnSnapshotFactory;
    private final PeriodTotalsRefresher totalsRefresher;
    private final DataScopeEnforcer dataScope;
    private final AuditService auditService;

    public RecordService(PeriodService periodService,
                         ItemService itemService,
                         PayRecordMapper recordMapper,
                         PayRecordItemMapper recordItemMapper,
                         ColumnSnapshotFactory columnSnapshotFactory,
                         PeriodTotalsRefresher totalsRefresher,
                         DataScopeEnforcer dataScope,
                         AuditService auditService) {
        this.periodService = periodService;
        this.itemService = itemService;
        this.recordMapper = recordMapper;
        this.recordItemMapper = recordItemMapper;
        this.columnSnapshotFactory = columnSnapshotFactory;
        this.totalsRefresher = totalsRefresher;
        this.dataScope = dataScope;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ grid

    /**
     * 一次查记录、一次查全部明细，在内存里组装 Map&lt;itemCode, amount&gt;。
     * 刻意不用「每行再查一次明细」的写法：一个期间几百上千行，N+1 会直接拖垮编制台。
     */
    public GridResponse grid(Long periodId) {
        dataScope.assertPeriodVisible(periodId);
        PayPeriodEntity period = periodService.requirePeriod(periodId);

        LambdaQueryWrapper<PayRecordEntity> recordQuery = Wrappers.<PayRecordEntity>lambdaQuery()
                .eq(PayRecordEntity::getPeriodId, periodId)
                .eq(PayRecordEntity::getCompanyId, period.getCompanyId())
                .orderByAsc(PayRecordEntity::getDeptId)
                .orderByAsc(PayRecordEntity::getEmpNo);
        // 数据范围下推到 SQL：SELF 只能看到自己那行，DEPT/DEPT_SUB 只能看到本部门
        dataScope.applyRecordFilter(recordQuery);
        List<PayRecordEntity> records = recordMapper.selectList(recordQuery);

        List<PayRecordItemEntity> allItems = records.isEmpty()
                ? List.of()
                : recordItemMapper.findByRecordIds(records.stream().map(PayRecordEntity::getId).toList());
        Map<Long, Map<String, String>> itemsByRecord = toItemMaps(allItems);
        List<GridRow> rows = records.stream()
                .map(r -> toRow(r, itemsByRecord.getOrDefault(r.getId(), Map.of())))
                .toList();
        // 查看工资也要留痕（设计 §4.7）：只记期间与行数，不记每条金额
        if (!rows.isEmpty()) {
            auditService.record("RECORD", periodId, "VIEW", "grid", null,
                    "periodId=" + periodId + ";rows=" + rows.size());
        }

        return new GridResponse(
                new GridPeriod(period.getId(), period.getPeriodYm(), period.getStatus()),
                columnSnapshotFactory.build(period.getCompanyId(), allItems),
                rows, rows.size());
    }

    private static Map<Long, Map<String, String>> toItemMaps(List<PayRecordItemEntity> items) {
        return items.stream()
                .collect(Collectors.groupingBy(PayRecordItemEntity::getRecordId,
                        Collectors.toMap(PayRecordItemEntity::getItemCode,
                                i -> money(i.getAmount()), (a, b) -> a, LinkedHashMap::new)));
    }

    // ------------------------------------------------------------------ changeset

    /**
     * 只提交改动的单元格。逐条按 (recordId, version) 做乐观锁更新：
     * 先全部尝试，收集全部冲突后再抛，保证前端一次就能看到所有被别人改过的行，
     * 而不是改一行报一行。
     */
    @Transactional
    public ChangesetResult applyChangeset(Long periodId, ChangesetReq req) {
        // 越权先于状态判断：受限账号看不到未锁定期间，直接 403 而不是先报"已锁定"
        dataScope.assertPeriodVisible(periodId);
        // 写事务内加行锁校验期间状态：普通 SELECT 的快照校验挡不住"校验通过后被锁定"
        periodService.assertWritableForUpdate(periodId);
        PayPeriodEntity period = periodService.requirePeriod(periodId);

        if (req == null || req.changes() == null || req.changes().isEmpty()) {
            throw new BizException(400, "changes 不能为空");
        }

        List<Long> conflicts = new ArrayList<>();
        Set<Long> touched = new LinkedHashSet<>();

        for (ChangesetChange change : req.changes()) {
            PayRecordEntity record = recordMapper.selectById(change.recordId());
            if (record == null || !periodId.equals(record.getPeriodId())) {
                throw new BizException(404, "核算记录不存在或不属于该期间：" + change.recordId());
            }
            dataScope.assertRecordVisible(record);
            // items 是变更的载体；缺失时若继续走只 bump version，会返回 200 却没写任何数据
            if (change.items() == null || change.items().isEmpty()) {
                throw new BizException(400, "changeset 缺少 items：" + change.recordId());
            }

            int expectedVersion = change.version() == null ? 0 : change.version();
            int updated = recordMapper.update(null, Wrappers.<PayRecordEntity>lambdaUpdate()
                    .set(PayRecordEntity::getVersionNo, expectedVersion + 1)
                    .eq(PayRecordEntity::getId, change.recordId())
                    .eq(PayRecordEntity::getPeriodId, periodId)
                    .eq(PayRecordEntity::getVersionNo, expectedVersion));
            if (updated == 0) {
                conflicts.add(change.recordId());
                continue;
            }

            for (ChangesetItem item : change.items()) {
                ItemChange applied = applyItem(period, change.recordId(), item);
                // 金额变更逐项留痕：谁在什么时候把哪一格从多少改成了多少
                if (applied.isChanged()) {
                    auditService.record("RECORD", change.recordId(), "UPDATE", item.itemCode(),
                            applied.oldValue() == null ? null : money(applied.oldValue()),
                            money(applied.newValue()));
                }
            }
            recalcTotals(period, change.recordId());
            touched.add(change.recordId());
        }

        if (!conflicts.isEmpty()) {
            // 抛出即回滚：不能出现"改了一半、剩下的人看到部分更新"的状态
            throw new VersionConflictException(conflicts);
        }

        List<GridRow> rows = touched.stream()
                .map(id -> toRow(recordMapper.selectById(id), loadItemMap(id)))
                .toList();
        // changeset 改的是明细金额，期间合计与列快照必须同步刷新
        totalsRefresher.refresh(period);
        return new ChangesetResult(touched.size(), rows);
    }

    private record ItemChange(BigDecimal oldValue, BigDecimal newValue) {
        boolean isChanged() {
            return oldValue == null || oldValue.compareTo(newValue) != 0;
        }
    }

    private ItemChange applyItem(PayPeriodEntity period, Long recordId, ChangesetItem item) {
        if (item == null || item.itemCode() == null || item.itemCode().isBlank()) {
            throw new BizException(400, "明细项编码不能为空");
        }
        PayItemEntity def = itemService.requireItem(period.getCompanyId(), item.itemCode());
        // 停用科目服务端也必须拒绝：前端把列置灰只是体验，不是防线
        if (def.getStatus() == null || def.getStatus() != 1) {
            throw new BizException(400, "该工资项已停用，不能修改：" + item.itemCode());
        }
        if (def.getEditable() == null || def.getEditable() != 1) {
            throw new BizException(400, "该工资项不允许手工编辑：" + item.itemCode());
        }

        BigDecimal amount = parseMoney(item.amount(), item.itemCode())
                .setScale(def.getPrecisionScale() == null ? MONEY_SCALE : def.getPrecisionScale(),
                        RoundingMode.HALF_UP);
        if (amount.signum() < 0) {
            throw new BizException(400, "明细金额不能为负数，扣款请使用减项科目：" + item.itemCode());
        }
        // DECIMAL(18,4) 上限 99999999999999.9999；超范围必须在服务层拦，否则落库报 500
        if (amount.abs().compareTo(MAX_DECIMAL_18_4) > 0) {
            throw new BizException(400, "金额超出允许范围（DECIMAL(18,4)）：" + item.itemCode());
        }

        PayRecordItemEntity existing = recordItemMapper.selectOne(
                Wrappers.<PayRecordItemEntity>lambdaQuery()
                        .eq(PayRecordItemEntity::getRecordId, recordId)
                        .eq(PayRecordItemEntity::getItemCode, item.itemCode())
                        .last("LIMIT 1"));
        if (existing != null) {
            BigDecimal oldAmount = existing.getAmount();
            existing.setAmount(amount);
            existing.setSource("MANUAL");
            recordItemMapper.updateById(existing);
            return new ItemChange(oldAmount, amount);
        }

        PayRecordItemEntity entity = new PayRecordItemEntity();
        entity.setRecordId(recordId);
        entity.setItemCode(def.getItemCode());
        entity.setItemName(def.getItemName());
        entity.setCategory(def.getCategory());
        entity.setDirection(def.getDirection());
        entity.setAmount(amount);
        entity.setSource("MANUAL");
        entity.setSortNo(def.getSortNo() == null ? 0 : def.getSortNo());
        recordItemMapper.insert(entity);
        return new ItemChange(null, amount);
    }

    /**
     * 重算一条记录的合计。按方向分别加总，扣款项不参与应发。
     *
     * <p>**不强制定标到 2 位**：每个工资项有自己的 {@code precision_scale}（允许 4 位），
     * 把合计硬写死成 scale=2 会让 4 位小数的明细触发 {@code RoundingMode.UNNECESSARY}
     * 抛算术后端 500，并且整批 changeset 回滚。合计就是明细的精确加总，
     * 展示层再按实际有效位数格式化（最少 2 位）。
     */
    private void recalcTotals(PayPeriodEntity period, Long recordId) {
        List<PayRecordItemEntity> items = recordItemMapper.findByRecordIds(List.of(recordId));
        Map<String, Boolean> includeByCode = includeInGross(period);
        BigDecimal gross = items.stream()
                .filter(i -> i.getDirection() != null && i.getDirection() == 1)
                .filter(i -> includeByCode.getOrDefault(i.getItemCode(), true))
                .map(PayRecordItemEntity::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deduction = sumByDirection(items, 2);

        PayRecordEntity update = new PayRecordEntity();
        update.setId(recordId);
        update.setGrossAmount(gross);
        update.setDeductionAmount(deduction);
        update.setNetAmount(gross.subtract(deduction));
        recordMapper.updateById(update);
    }

    /**
     * 科目是否计入应发合计。录入侧必须与计算侧同一口径，否则改一格非应发项
     * 会让 gross 凭空虚增（领域层忽略了 include_in_gross 的历史问题）。
     */
    private Map<String, Boolean> includeInGross(PayPeriodEntity period) {
        return itemService.list(period.getCompanyId(), null, null).stream()
                .collect(Collectors.toMap(d -> d.itemCode(),
                        d -> !Boolean.FALSE.equals(d.includeInGross()), (a, b) -> a));
    }

    private static BigDecimal sumByDirection(List<PayRecordItemEntity> items, int direction) {
        return items.stream()
                .filter(i -> i.getDirection() != null && i.getDirection() == direction)
                .map(PayRecordItemEntity::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, String> loadItemMap(Long recordId) {
        return toItemMaps(recordItemMapper.findByRecordIds(List.of(recordId)))
                .getOrDefault(recordId, Map.of());
    }

    private static BigDecimal parseMoney(String raw, String itemCode) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(400, "金额不能为空：" + itemCode);
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(400, "金额格式不正确：" + itemCode + "=" + raw);
        }
    }

    /**
     * 金额格式化：至少 2 位小数，超过 2 位的有效小数原样保留。
     * 强制两位会把 4 位小数的明细"吃掉"，导致前端校验「合计 === 明细加总」失败。
     */
    private static String money(BigDecimal value) {
        return Money.format(value);
    }

    private static GridRow toRow(PayRecordEntity r, Map<String, String> items) {
        return new GridRow(
                r.getId(),
                r.getVersionNo(),
                r.getEmployeeId(),
                r.getEmpNo(),
                r.getEmpName(),
                r.getDeptId(),
                r.getDeptName(),
                r.getEmployeeType(),
                r.getJobLevel(),
                r.getSeniorityYears() == null ? "0.00"
                        : r.getSeniorityYears().setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString(),
                money(r.getGrossAmount()),
                money(r.getDeductionAmount()),
                money(r.getNetAmount()),
                items);
    }
}
