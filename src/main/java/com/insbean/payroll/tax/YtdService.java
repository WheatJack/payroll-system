package com.insbean.payroll.tax;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.record.mapper.PayRecordItemMapper;
import com.insbean.payroll.record.mapper.PayRecordMapper;
import com.insbean.payroll.tax.mapper.PayYtdMapper;
import com.insbean.payroll.tax.mapper.SpecialDeductionMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 年度累计台账联动：锁定累加、解锁减回（同一事务内由 PeriodService 调用）。
 */
@Service
public class YtdService {

    public static final String SOCIAL_INS = "SOCIAL_INS";
    public static final String HOUSING_FUND = "HOUSING_FUND";
    public static final String TAX = "TAX";

    private final PayRecordMapper recordMapper;
    private final PayRecordItemMapper recordItemMapper;
    private final SpecialDeductionMapper specialMapper;
    private final PayYtdMapper ytdMapper;

    public YtdService(PayRecordMapper recordMapper,
                      PayRecordItemMapper recordItemMapper,
                      SpecialDeductionMapper specialMapper,
                      PayYtdMapper ytdMapper) {
        this.recordMapper = recordMapper;
        this.recordItemMapper = recordItemMapper;
        this.specialMapper = specialMapper;
        this.ytdMapper = ytdMapper;
    }

    /**
     * @param reverse true=解锁时减回
     */
    public void accumulate(PayPeriodEntity period, boolean reverse) {
        List<PayRecordEntity> records = recordMapper.selectList(
                Wrappers.<PayRecordEntity>lambdaQuery()
                        .eq(PayRecordEntity::getPeriodId, period.getId())
                        .eq(PayRecordEntity::getCompanyId, period.getCompanyId())
                        .eq(PayRecordEntity::getDeleted, 0));
        if (records.isEmpty()) {
            return;
        }
        List<PayRecordItemEntity> items = recordItemMapper.findByRecordIds(
                records.stream().map(PayRecordEntity::getId).toList());
        Map<Long, List<PayRecordItemEntity>> itemsByRecord = items.stream()
                .collect(Collectors.groupingBy(PayRecordItemEntity::getRecordId));

        int taxYear = YearMonth.parse(period.getPeriodYm()).getYear();
        LocalDate periodEnd = YearMonth.parse(period.getPeriodYm()).atEndOfMonth();

        for (PayRecordEntity record : records) {
            Map<String, BigDecimal> byCode = itemsByRecord
                    .getOrDefault(record.getId(), List.of()).stream()
                    .collect(Collectors.toMap(PayRecordItemEntity::getItemCode,
                            i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount(),
                            BigDecimal::add));
            BigDecimal special = specialMapper.sumEffectiveAt(record.getEmployeeId(), periodEnd);
            if (special == null) {
                special = BigDecimal.ZERO;
            }
            if (reverse) {
                ytdMapper.subtractContribution(record.getEmployeeId(), taxYear,
                        TaxService.nvl(record.getGrossAmount()),
                        byCode.getOrDefault(SOCIAL_INS, BigDecimal.ZERO),
                        byCode.getOrDefault(HOUSING_FUND, BigDecimal.ZERO),
                        special,
                        byCode.getOrDefault(TAX, BigDecimal.ZERO),
                        period.getPeriodYm());
            } else {
                ytdMapper.addContribution(record.getEmployeeId(), taxYear,
                        record.getCompanyId(),
                        TaxService.nvl(record.getGrossAmount()),
                        byCode.getOrDefault(SOCIAL_INS, BigDecimal.ZERO),
                        byCode.getOrDefault(HOUSING_FUND, BigDecimal.ZERO),
                        special,
                        byCode.getOrDefault(TAX, BigDecimal.ZERO),
                        period.getPeriodYm());
            }
        }
    }
}
