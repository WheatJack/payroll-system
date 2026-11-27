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
            BigDecimal seniorityPay = ctx.seniorityPerYearValue()
                    .multiply(BigDecimal.valueOf(seniorityYears(ctx.hireDate(), ctx.periodEnd())));
            items.add(item(ctx, SENIORITY_PAY, seniorityPay, null, PaySource.COMPUTED));
        }

        // 变动项是已经确定的金额，原样计入，不折算也不再计算
        ctx.variableItems().forEach((code, raw) ->
                items.add(item(ctx, code, raw, null, PaySource.VARIABLE)));

        BigDecimal gross = sumOf(items, PayDirection.INCREASE, true);
        BigDecimal deduction = sumOf(items, PayDirection.DECREASE, false);
        return new PayrollResult(List.copyOf(items), gross, deduction, gross.subtract(deduction));
    }

    /** 工龄：期间末日与入职日期之间的整年数，向下取整。 */
    public int seniorityYears(LocalDate hireDate, LocalDate periodEnd) {
        if (hireDate == null || periodEnd == null || hireDate.isAfter(periodEnd)) {
            return 0;
        }
        return Period.between(hireDate, periodEnd).getYears();
    }

    /** 返回 null 表示全月在岗，不做折算。分母取期间参数（hpd），缺省 21.75。 */
    private BigDecimal prorationFactor(EmployeePayContext ctx) {
        if (ctx.payableDays() == null) {
            return null;
        }
        return BigDecimal.valueOf(ctx.payableDays())
                .divide(ctx.payableDaysDivisor(), PayrollRules.CALC_SCALE, RoundingMode.HALF_UP);
    }

    private PayItemResult item(EmployeePayContext ctx, String code, BigDecimal raw,
                               BigDecimal factor, PaySource source) {
        PayItemDef def = ctx.itemDefs().get(code);
        if (def == null) {
            throw new IllegalStateException("工资项定义缺失: " + code);
        }
        // 按科目自己的精度舍入一次。统一按 2 位会把 4 位科目的金额静默改掉，
        // 导致"生成路径"与"编制台/导入路径"算出不同的钱。
        // scale=0 是合法精度（取整），不能当成"未配置"回落 2
        int scale = def.precisionScale() >= 0 ? def.precisionScale() : PayrollRules.AMOUNT_SCALE;
        BigDecimal amount = (factor == null ? raw : raw.multiply(factor))
                .setScale(scale, RoundingMode.HALF_UP);
        return new PayItemResult(def.code(), def.name(), def.category(), def.direction(),
                amount, source, def.includeInGross());
    }

    /**
     * 合计只累加已舍入的明细，不再二次舍入——明细精度可以不同（2 位/4 位），
     * 强制统一定标要么吃精度要么抛异常；合计就是精确加总。
     *
     * @param onlyGrossIncluded 只累加 include_in_gross=true 的项（仅对应发合计有意义）
     */
    private BigDecimal sumOf(List<PayItemResult> items, PayDirection direction, boolean onlyGrossIncluded) {
        return items.stream()
                .filter(i -> i.direction() == direction)
                .filter(i -> !onlyGrossIncluded || i.includeInGross())
                .map(PayItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
