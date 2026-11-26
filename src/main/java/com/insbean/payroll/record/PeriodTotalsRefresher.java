package com.insbean.payroll.record;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.period.mapper.PeriodMapper;
import com.insbean.payroll.record.dto.GridColumn;
import com.insbean.payroll.record.mapper.PayRecordItemMapper;
import com.insbean.payroll.record.mapper.PayRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 回写期间级汇总与列快照。
 *
 * <p>生成与 changeset 保存都会改动明细金额，两条路径必须用同一份逻辑刷新，
 * 否则列表页显示的期间合计会长期停在生成时的旧值（评审 P2 的问题）。
 */
@Slf4j
@Component
public class PeriodTotalsRefresher {

    private final PayRecordMapper recordMapper;
    private final PayRecordItemMapper recordItemMapper;
    private final PeriodMapper periodMapper;
    private final ColumnSnapshotFactory columnSnapshotFactory;
    private final ObjectMapper objectMapper;

    public PeriodTotalsRefresher(PayRecordMapper recordMapper,
                                 PayRecordItemMapper recordItemMapper,
                                 PeriodMapper periodMapper,
                                 ColumnSnapshotFactory columnSnapshotFactory,
                                 ObjectMapper objectMapper) {
        this.recordMapper = recordMapper;
        this.recordItemMapper = recordItemMapper;
        this.periodMapper = periodMapper;
        this.columnSnapshotFactory = columnSnapshotFactory;
        this.objectMapper = objectMapper;
    }

    public void refresh(PayPeriodEntity period) {
        List<PayRecordEntity> records = recordMapper.selectList(Wrappers.<PayRecordEntity>lambdaQuery()
                .eq(PayRecordEntity::getPeriodId, period.getId())
                .eq(PayRecordEntity::getCompanyId, period.getCompanyId()));

        BigDecimal gross = sum(records.stream().map(PayRecordEntity::getGrossAmount).toList());
        BigDecimal net = sum(records.stream().map(PayRecordEntity::getNetAmount).toList());

        List<PayRecordItemEntity> items = records.isEmpty()
                ? List.of()
                : recordItemMapper.findByRecordIds(records.stream().map(PayRecordEntity::getId).toList());
        List<GridColumn> columns = columnSnapshotFactory.build(period.getCompanyId(), items);

        PayPeriodEntity update = new PayPeriodEntity();
        update.setId(period.getId());
        update.setEmployeeCount(records.size());
        update.setTotalGross(gross);
        update.setTotalNet(net);
        update.setColumnSnapshot(toJson(columns));
        periodMapper.updateById(update);
    }

    /** 列快照用于导出：明细可能含已停用科目，导出列不能取当前启用科目。 */
    private String toJson(List<GridColumn> columns) {
        try {
            return objectMapper.writeValueAsString(columns);
        } catch (Exception e) {
            log.warn("列快照序列化失败，本次不写快照", e);
            return null;
        }
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
