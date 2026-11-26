package com.insbean.payroll.record;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.common.VersionConflictException;
import com.insbean.payroll.domain.entity.PayItemEntity;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.item.ItemService;
import com.insbean.payroll.item.dto.PayItemDefDTO;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.mapper.PeriodMapper;
import com.insbean.payroll.record.dto.ChangesetChange;
import com.insbean.payroll.record.dto.ChangesetItem;
import com.insbean.payroll.record.dto.ChangesetReq;
import com.insbean.payroll.record.dto.ChangesetResult;
import com.insbean.payroll.record.dto.GridColumn;
import com.insbean.payroll.record.dto.GridPeriod;
import com.insbean.payroll.record.dto.GridResponse;
import com.insbean.payroll.record.dto.GridRow;
import com.insbean.payroll.run.mapper.PayRecordItemMapper;
import com.insbean.payroll.run.mapper.PayRecordMapper;
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
 *   <li>写之前必须过 {@code assertWritable}，锁定/归档期间连一次 UPDATE 都不发。</li>
 * </ul>
 */
@Service
public class RecordService {

    private static final int MONEY_SCALE = 2;

    private final PeriodService periodService;
    private final ItemService itemService;
    private final PayRecordMapper recordMapper;
    private final PayRecordItemMapper recordItemMapper;
    private final PeriodMapper periodMapper;
    private final DataScopeEnforcer dataScope;

    public RecordService(PeriodService periodService,
                         ItemService itemService,
                         PayRecordMapper recordMapper,
                         PayRecordItemMapper recordItemMapper,
                         PeriodMapper periodMapper,
                         DataScopeEnforcer dataScope) {
        this.periodService = periodService;
        this.itemService = itemService;
        this.recordMapper = recordMapper;
        this.recordItemMapper = recordItemMapper;
        this.periodMapper = periodMapper;
        this.dataScope = dataScope;
    }

    // ------------------------------------------------------------------ grid

    /**
     * 一次查记录、一次查全部明细，在内存里组装 Map&lt;itemCode, amount&gt;。
     * 刻意不用「每行再查一次明细」的写法：一个期间几百上千行，N+1 会直接拖垮编制台。
     */
    public GridResponse grid(Long periodId) {
        dataScope.assertPeriodVisible(periodId);
        PayPeriodEntity period = periodService.requirePeriod(periodId);

        List<GridColumn> columns = itemService.listEnabled(period.getCompanyId()).stream()
                .map(d -> new GridColumn(d.itemCode(), d.itemName(), d.category(),
                        d.direction(), d.precisionScale(), d.editable()))
                .toList();

        LambdaQueryWrapper<PayRecordEntity> recordQuery = Wrappers.<PayRecordEntity>lambdaQuery()
                .eq(PayRecordEntity::getPeriodId, periodId)
                .eq(PayRecordEntity::getCompanyId, period.getCompanyId())
                .orderByAsc(PayRecordEntity::getDeptId)
                .orderByAsc(PayRecordEntity::getEmpNo);
        // 数据范围下推到 SQL：SELF 只能看到自己那行，DEPT/DEPT_SUB 只能看到本部门
        dataScope.applyRecordFilter(recordQuery);
        List<PayRecordEntity> records = recordMapper.selectList(recordQuery);

        Map<Long, Map<String, String>> itemsByRecord = loadItemMaps(records);
        List<GridRow> rows = records.stream()
                .map(r -> toRow(r, itemsByRecord.getOrDefault(r.getId(), Map.of())))
                .toList();

        return new GridResponse(
                new GridPeriod(period.getId(), period.getPeriodYm(), period.getStatus()),
                withUsedButDisabledColumns(period.getCompanyId(), columns, itemsByRecord),
                rows, rows.size());
    }

    /**
     * 工资表列 = 启用科目 ∪ 本期间明细里出现过的科目。
     *
     * <p>科目在生成之后被停用时，只取启用科目会让明细里的键不在 columns 里，
     * 前端按 columns 求和必然对不上合计。停用列照样展示，只是不可编辑。
     */
    private List<GridColumn> withUsedButDisabledColumns(Long companyId,
                                                        List<GridColumn> enabledColumns,
                                                        Map<Long, Map<String, String>> itemsByRecord) {
        Set<String> known = enabledColumns.stream().map(GridColumn::itemCode)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> used = itemsByRecord.values().stream()
                .flatMap(m -> m.keySet().stream())
                .filter(code -> !known.contains(code))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (used.isEmpty()) {
            return enabledColumns;
        }

        Map<String, PayItemEntity> allItems = itemService.list(companyId, null, null).stream()
                .collect(Collectors.toMap(PayItemDefDTO::itemCode,
                        d -> toEntity(d), (a, b) -> a));

        List<GridColumn> merged = new ArrayList<>(enabledColumns);
        for (String code : used) {
            PayItemEntity def = allItems.get(code);
            merged.add(new GridColumn(
                    code,
                    def == null ? code : def.getItemName(),
                    def == null ? "VARIABLE" : def.getCategory(),
                    def == null ? 1 : def.getDirection(),
                    def == null ? MONEY_SCALE : def.getPrecisionScale(),
                    false));
        }
        return merged;
    }

    private static PayItemEntity toEntity(PayItemDefDTO dto) {
        PayItemEntity entity = new PayItemEntity();
        entity.setItemCode(dto.itemCode());
        entity.setItemName(dto.itemName());
        entity.setCategory(dto.category());
        entity.setDirection(dto.direction());
        entity.setPrecisionScale(dto.precisionScale());
        return entity;
    }

    private Map<Long, Map<String, String>> loadItemMaps(List<PayRecordEntity> records) {
        if (records.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = records.stream().map(PayRecordEntity::getId).toList();
        return recordItemMapper.findByRecordIds(ids).stream()
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
        // 写事务内加行锁校验期间状态：普通 SELECT 的快照校验挡不住"校验通过后被锁定"
        periodService.assertWritableForUpdate(periodId);
        dataScope.assertPeriodVisible(periodId);
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

            if (change.items() != null) {
                for (ChangesetItem item : change.items()) {
                    applyItem(period, change.recordId(), item);
                }
            }
            recalcTotals(change.recordId());
            touched.add(change.recordId());
        }

        if (!conflicts.isEmpty()) {
            // 抛出即回滚：不能出现"改了一半、剩下的人看到部分更新"的状态
            throw new VersionConflictException(conflicts);
        }

        List<GridRow> rows = touched.stream()
                .map(id -> toRow(recordMapper.selectById(id), loadItemMap(id)))
                .toList();
        // changeset 改的是明细金额，期间合计必须同步刷新，否则列表页显示的合计永远是生成时的旧值
        refreshPeriodTotals(period);
        return new ChangesetResult(touched.size(), rows);
    }

    private void refreshPeriodTotals(PayPeriodEntity period) {
        List<PayRecordEntity> records = recordMapper.selectList(Wrappers.<PayRecordEntity>lambdaQuery()
                .eq(PayRecordEntity::getPeriodId, period.getId())
                .eq(PayRecordEntity::getCompanyId, period.getCompanyId()));
        BigDecimal gross = records.stream().map(PayRecordEntity::getGrossAmount)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = records.stream().map(PayRecordEntity::getNetAmount)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        PayPeriodEntity update = new PayPeriodEntity();
        update.setId(period.getId());
        update.setEmployeeCount(records.size());
        update.setTotalGross(gross);
        update.setTotalNet(net);
        periodMapper.updateById(update);
    }

    private void applyItem(PayPeriodEntity period, Long recordId, ChangesetItem item) {
        if (item == null || item.itemCode() == null || item.itemCode().isBlank()) {
            throw new BizException(400, "明细项编码不能为空");
        }
        PayItemEntity def = itemService.requireItem(period.getCompanyId(), item.itemCode());
        if (def.getEditable() == null || def.getEditable() != 1) {
            throw new BizException(400, "该工资项不允许手工编辑：" + item.itemCode());
        }

        BigDecimal amount = parseMoney(item.amount(), item.itemCode())
                .setScale(def.getPrecisionScale() == null ? MONEY_SCALE : def.getPrecisionScale(),
                        RoundingMode.HALF_UP);
        if (amount.signum() < 0) {
            throw new BizException(400, "明细金额不能为负数，扣款请使用减项科目：" + item.itemCode());
        }

        PayRecordItemEntity existing = recordItemMapper.selectOne(
                Wrappers.<PayRecordItemEntity>lambdaQuery()
                        .eq(PayRecordItemEntity::getRecordId, recordId)
                        .eq(PayRecordItemEntity::getItemCode, item.itemCode())
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setAmount(amount);
            existing.setSource("MANUAL");
            recordItemMapper.updateById(existing);
            return;
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
    }

    /**
     * 重算一条记录的合计。按方向分别加总，扣款项不参与应发。
     *
     * <p>**不强制定标到 2 位**：每个工资项有自己的 {@code precision_scale}（允许 4 位），
     * 把合计硬写死成 scale=2 会让 4 位小数的明细触发 {@code RoundingMode.UNNECESSARY}
     * 抛算术后端 500，并且整批 changeset 回滚。合计就是明细的精确加总，
     * 展示层再按实际有效位数格式化（最少 2 位）。
     */
    private void recalcTotals(Long recordId) {
        List<PayRecordItemEntity> items = recordItemMapper.findByRecordIds(List.of(recordId));
        BigDecimal gross = sumByDirection(items, 1);
        BigDecimal deduction = sumByDirection(items, 2);

        PayRecordEntity update = new PayRecordEntity();
        update.setId(recordId);
        update.setGrossAmount(gross);
        update.setDeductionAmount(deduction);
        update.setNetAmount(gross.subtract(deduction));
        recordMapper.updateById(update);
    }

    private static BigDecimal sumByDirection(List<PayRecordItemEntity> items, int direction) {
        return items.stream()
                .filter(i -> i.getDirection() != null && i.getDirection() == direction)
                .map(PayRecordItemEntity::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, String> loadItemMap(Long recordId) {
        return recordItemMapper.findByRecordIds(List.of(recordId)).stream()
                .collect(Collectors.toMap(PayRecordItemEntity::getItemCode,
                        i -> money(i.getAmount()), (a, b) -> a, LinkedHashMap::new));
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
        if (value == null) {
            return "0.00";
        }
        int effective = Math.max(MONEY_SCALE, Math.max(0, value.stripTrailingZeros().scale()));
        return value.setScale(effective, RoundingMode.HALF_UP).toPlainString();
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
