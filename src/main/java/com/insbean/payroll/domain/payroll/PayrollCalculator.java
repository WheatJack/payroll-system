package com.insbean.payroll.domain.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * 单条核算记录的工资计算。
 *
 * <p>纯领域逻辑：不依赖 Spring、不依赖数据库、不读写任何外部状态，可直接单元测试。
 * 规则来源见设计文档 §4.3.1。
 *
 * <p>金额铁律：每个明细项只舍入一次，合计等于明细直接加总，绝不做二次舍入。
 */
public class PayrollCalculator {

    public static final String BASE_SALARY = "BASE_SALARY";
    public static final String SENIORITY_PAY = "SENIORITY_PAY";

    public PayrollResult calculate(EmployeePayContext ctx) {
        BigDecimal factor = prorationFactor(ctx);

        List<PayItemResult> items = new ArrayList<>();
        items.add(item(ctx, BASE_SALARY, ctx.baseSalary(), factor, PaySource.FIXED));

        // 固定津贴与基本工资同口径：不足整月同比折算
        ctx.fixedItems().forEach((code, raw) ->
                items.add(item(ctx, code, raw, factor, PaySource.FIXED)));

        // 工龄工资按整年计算，不足整月不折算（C13）
        if (ctx.itemDefs().containsKey(SENIORITY_PAY)) {
            BigDecimal seniorityPay = PayrollRules.SENIORITY_PAY_PER_YEAR
                    .multiply(BigDecimal.valueOf(seniorityYears(ctx.hireDate(), ctx.periodEnd())));
            items.add(item(ctx, SENIORITY_PAY, seniorityPay, null, PaySource.COMPUTED));
        }

        // 变动项是已经确定的金额，原样计入，不折算也不再计算
        ctx.variableItems().forEach((code, raw) ->
                items.add(item(ctx, code, raw, null, PaySource.VARIABLE)));

        BigDecimal gross = sumOf(items, PayDirection.INCREASE);
        BigDecimal deduction = sumOf(items, PayDirection.DECREASE);
        return new PayrollResult(List.copyOf(items), gross, deduction, gross.subtract(deduction));
    }

    /** 工龄：期间末日与入职日期之间的整年数，向下取整。 */
    public int seniorityYears(LocalDate hireDate, LocalDate periodEnd) {
        if (hireDate == null || periodEnd == null || hireDate.isAfter(periodEnd)) {
            return 0;
        }
        return Period.between(hireDate, periodEnd).getYears();
    }

    /** 返回 null 表示全月在岗，不做折算。 */
    private BigDecimal prorationFactor(EmployeePayContext ctx) {
        if (ctx.payableDays() == null) {
            return null;
        }
        return BigDecimal.valueOf(ctx.payableDays())
                .divide(PayrollRules.MONTHLY_PAYABLE_DAYS, PayrollRules.CALC_SCALE, RoundingMode.HALF_UP);
    }

    private PayItemResult item(EmployeePayContext ctx, String code, BigDecimal raw,
                               BigDecimal factor, PaySource source) {
        PayItemDef def = ctx.itemDefs().get(code);
        if (def == null) {
            throw new IllegalStateException("工资项定义缺失: " + code);
        }
        BigDecimal amount = (factor == null ? raw : raw.multiply(factor))
                .setScale(PayrollRules.AMOUNT_SCALE, RoundingMode.HALF_UP);
        return new PayItemResult(def.code(), def.name(), def.category(), def.direction(), amount, source);
    }

    /**
     * 合计只累加已舍入的明细，用 UNNECESSARY 断言"重新定标不会丢失精度"——
     * 一旦有人改动明细精度，这里会立刻抛错，而不是悄悄产生一分钱误差。
     */
    private BigDecimal sumOf(List<PayItemResult> items, PayDirection direction) {
        return items.stream()
                .filter(i -> i.direction() == direction)
                .map(PayItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PayrollRules.AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }
}
