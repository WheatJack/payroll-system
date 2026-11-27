package com.insbean.payroll.tax;

import com.insbean.payroll.record.mapper.PayRecordMapper;
import com.insbean.payroll.tax.mapper.InsuranceParamMapper;
import com.insbean.payroll.tax.mapper.SocialInsuranceMapper;
import com.insbean.payroll.tax.mapper.SpecialDeductionMapper;
import com.insbean.payroll.tax.mapper.TaxBracketMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 社保公积金与累计预扣个税。
 *
 * <p>个税依赖累计台账与参数表，故意**不放进纯函数 PayrollCalculator**：
 * 计算器只算应发，本服务算完社保/公积金/税后由 GenerateService 追加为扣款明细。
 */
@Service
public class TaxService {

    /** 每月基本减除费用（起征点）。 */
    static final BigDecimal MONTHLY_BASIC_DEDUCTION = new BigDecimal("5000");

    private final TaxBracketMapper bracketMapper;
    private final InsuranceParamMapper paramMapper;
    private final SocialInsuranceMapper socialMapper;
    private final SpecialDeductionMapper specialMapper;
    private final PayRecordMapper recordMapper;

    public TaxService(TaxBracketMapper bracketMapper,
                      InsuranceParamMapper paramMapper,
                      SocialInsuranceMapper socialMapper,
                      SpecialDeductionMapper specialMapper,
                      PayRecordMapper recordMapper) {
        this.bracketMapper = bracketMapper;
        this.paramMapper = paramMapper;
        this.socialMapper = socialMapper;
        this.specialMapper = specialMapper;
        this.recordMapper = recordMapper;
    }

    /** 社保（养老+医疗+失业）与公积金个人部分。 */
    public record InsuranceResult(BigDecimal social, BigDecimal fund) {
        static final InsuranceResult ZERO = new InsuranceResult(
                new BigDecimal("0.00"), new BigDecimal("0.00"));
    }

    public InsuranceResult insurance(Long employeeId, String periodYm, LocalDate periodEnd,
                                     BigDecimal currentGross) {
        SocialInsuranceEntity info = socialMapper.effectiveAt(employeeId, periodEnd);
        if (info == null || info.getCityCode() == null) {
            return InsuranceResult.ZERO;
        }
        Map<String, InsuranceParamEntity> params = paramMapper
                .findEffective(info.getCityCode(), periodEnd).stream()
                .collect(Collectors.toMap(InsuranceParamEntity::getInsuranceType,
                        Function.identity(), (a, b) -> a));
        InsuranceParamEntity pension = params.get(InsuranceParamEntity.PENSION);
        if (pension == null && params.isEmpty()) {
            return InsuranceResult.ZERO;
        }

        BigDecimal rawBase = info.getSocialBase() != null
                ? info.getSocialBase()
                : recordMapper.averageRecentGross(employeeId, periodYm);
        if (rawBase == null || rawBase.signum() <= 0) {
            rawBase = currentGross;
        }
        BigDecimal base = clampBase(rawBase, pension != null ? pension
                : params.values().iterator().next());

        BigDecimal social = BigDecimal.ZERO;
        for (String type : List.of(InsuranceParamEntity.PENSION,
                InsuranceParamEntity.MEDICAL, InsuranceParamEntity.UNEMPLOYMENT)) {
            InsuranceParamEntity param = params.get(type);
            if (param == null) {
                continue;
            }
            BigDecimal amount = round2(base.multiply(param.getPersonalRate()));
            if (param.getExtraAmount() != null) {
                amount = amount.add(param.getExtraAmount());
            }
            social = social.add(amount);
        }

        BigDecimal fund = BigDecimal.ZERO;
        InsuranceParamEntity fundParam = params.get(InsuranceParamEntity.HOUSING_FUND);
        BigDecimal fundRate = info.getHousingFundRate() != null
                ? info.getHousingFundRate()
                : (fundParam == null ? BigDecimal.ZERO : fundParam.getPersonalRate());
        if (fundRate.signum() > 0) {
            fund = round2(base.multiply(fundRate));
        }
        return new InsuranceResult(round2(social), round2(fund));
    }

    public BigDecimal specialDeduction(Long employeeId, LocalDate periodEnd) {
        BigDecimal sum = specialMapper.sumEffectiveAt(employeeId, periodEnd);
        return sum == null ? new BigDecimal("0.00") : round2(sum);
    }

    /**
     * 累计预扣法：
     * taxable = 累计收入 − months×5000 − 累计社保 − 累计公积金 − 累计专项附加；
     * 本期 = max(累计应纳税额 − 累计已预扣, 0)。
     */
    public BigDecimal computeTax(int months, BigDecimal cumIncome, BigDecimal cumSocial,
                                 BigDecimal cumFund, BigDecimal cumSpecial, BigDecimal cumTaxPaid) {
        BigDecimal taxable = nvl(cumIncome)
                .subtract(MONTHLY_BASIC_DEDUCTION.multiply(BigDecimal.valueOf(months)))
                .subtract(nvl(cumSocial))
                .subtract(nvl(cumFund))
                .subtract(nvl(cumSpecial));
        if (taxable.signum() <= 0) {
            return new BigDecimal("0.00");
        }
        TaxBracketEntity bracket = bracketMapper.findByTaxable(taxable);
        if (bracket == null) {
            return new BigDecimal("0.00");
        }
        BigDecimal cumulativeTax = round2(taxable.multiply(bracket.getRate())
                .subtract(nvl(bracket.getQuickDeduction())));
        if (cumulativeTax.signum() < 0) {
            cumulativeTax = BigDecimal.ZERO;
        }
        BigDecimal payable = cumulativeTax.subtract(nvl(cumTaxPaid));
        return payable.signum() < 0 ? new BigDecimal("0.00") : round2(payable);
    }

    private static BigDecimal clampBase(BigDecimal raw, InsuranceParamEntity param) {
        BigDecimal base = raw;
        if (param.getBaseMin() != null && base.compareTo(param.getBaseMin()) < 0) {
            base = param.getBaseMin();
        }
        if (param.getBaseMax() != null && base.compareTo(param.getBaseMax()) > 0) {
            base = param.getBaseMax();
        }
        return round2(base);
    }

    public static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
