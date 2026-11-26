package com.insbean.payroll.domain.payroll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核算正确性的黄金用例集。每个期望值都是按 §4.3.1 规则手工推算的，
 * 精确到分——这是唯一能证明"系统算得对"的证据。
 */
class PayrollCalculatorTest {

    private final PayrollCalculator calculator = new PayrollCalculator();

    private static final Map<String, PayItemDef> DEFS = new LinkedHashMap<>();

    static {
        DEFS.put(PayrollCalculator.BASE_SALARY,
                new PayItemDef(PayrollCalculator.BASE_SALARY, "基本工资", PayCategory.FIXED, PayDirection.INCREASE));
        DEFS.put("POST_ALLOW",
                new PayItemDef("POST_ALLOW", "岗位津贴", PayCategory.FIXED, PayDirection.INCREASE));
        DEFS.put(PayrollCalculator.SENIORITY_PAY,
                new PayItemDef(PayrollCalculator.SENIORITY_PAY, "工龄工资", PayCategory.COMPUTED, PayDirection.INCREASE));
        DEFS.put("PERF_BONUS",
                new PayItemDef("PERF_BONUS", "绩效奖金", PayCategory.VARIABLE, PayDirection.INCREASE));
        DEFS.put("ATT_DEDUCT",
                new PayItemDef("ATT_DEDUCT", "考勤扣款", PayCategory.VARIABLE, PayDirection.DECREASE));
    }

    private EmployeePayContext ctx(String baseSalary, LocalDate hireDate, Integer payableDays,
                                   Map<String, BigDecimal> fixed, Map<String, BigDecimal> variable) {
        return new EmployeePayContext(
                1L, "E001", "张三",
                new BigDecimal(baseSalary),
                hireDate, null,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                payableDays,
                DEFS, fixed, variable);
    }

    @Test
    @DisplayName("全月在岗：基本工资足额 + 工龄工资按整年")
    void fullMonth() {
        PayrollResult r = calculator.calculate(
                ctx("8000", LocalDate.of(2020, 3, 1), null, Map.of(), Map.of()));

        assertThat(amountOf(r, PayrollCalculator.BASE_SALARY)).isEqualByComparingTo("8000.00");
        // 2020-03-01 → 2026-11-30 = 6 年 8 个月，向下取整为 6 年
        assertThat(amountOf(r, PayrollCalculator.SENIORITY_PAY)).isEqualByComparingTo("600.00");
        assertThat(r.grossAmount()).isEqualByComparingTo("8600.00");
        assertThat(r.netAmount()).isEqualByComparingTo("8600.00");
    }

    @Test
    @DisplayName("入职当月：日工资 = 月薪 ÷ 21.75，按实际计薪天数折算")
    void midMonthJoin() {
        PayrollResult r = calculator.calculate(
                ctx("8000", LocalDate.of(2020, 3, 1), 15, Map.of(), Map.of()));

        // 8000 ÷ 21.75 × 15 = 5517.2413793... → 5517.24
        assertThat(amountOf(r, PayrollCalculator.BASE_SALARY)).isEqualByComparingTo("5517.24");
        // 工龄工资不随不足月折算
        assertThat(amountOf(r, PayrollCalculator.SENIORITY_PAY)).isEqualByComparingTo("600.00");
        assertThat(r.grossAmount()).isEqualByComparingTo("6117.24");
    }

    @Test
    @DisplayName("固定津贴与基本工资同口径折算")
    void fixedAllowanceProrated() {
        PayrollResult r = calculator.calculate(
                ctx("8000", LocalDate.of(2020, 3, 1), 15,
                        Map.of("POST_ALLOW", new BigDecimal("1000")), Map.of()));

        // 1000 ÷ 21.75 × 15 = 689.6551... → 689.66
        assertThat(amountOf(r, "POST_ALLOW")).isEqualByComparingTo("689.66");
        assertThat(r.grossAmount()).isEqualByComparingTo("6806.90");
    }

    @Test
    @DisplayName("变动项按导入金额原样计入，不折算")
    void variableItemIsNotProrated() {
        PayrollResult r = calculator.calculate(
                ctx("8000", LocalDate.of(2020, 3, 1), 15,
                        Map.of(), Map.of("PERF_BONUS", new BigDecimal("2000"))));

        assertThat(amountOf(r, "PERF_BONUS")).isEqualByComparingTo("2000.00");
        assertThat(r.grossAmount()).isEqualByComparingTo("8117.24");
    }

    @Test
    @DisplayName("扣款项计入扣款合计，实发 = 应发 − 扣款")
    void deductionAffectsNet() {
        PayrollResult r = calculator.calculate(
                ctx("8000", LocalDate.of(2020, 3, 1), null,
                        Map.of(), Map.of("ATT_DEDUCT", new BigDecimal("300"))));

        assertThat(r.grossAmount()).isEqualByComparingTo("8600.00");
        assertThat(r.deductionAmount()).isEqualByComparingTo("300.00");
        assertThat(r.netAmount()).isEqualByComparingTo("8300.00");
    }

    @Test
    @DisplayName("金额铁律：合计恒等于明细加总，无二次舍入")
    void totalsEqualSumOfItems() {
        PayrollResult r = calculator.calculate(
                ctx("7333.33", LocalDate.of(2023, 7, 17), 11,
                        Map.of("POST_ALLOW", new BigDecimal("333.33")),
                        Map.of("PERF_BONUS", new BigDecimal("1234.56"),
                                "ATT_DEDUCT", new BigDecimal("77.77"))));

        BigDecimal sumIncrease = r.items().stream()
                .filter(i -> i.direction() == PayDirection.INCREASE)
                .map(PayItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumDecrease = r.items().stream()
                .filter(i -> i.direction() == PayDirection.DECREASE)
                .map(PayItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(r.grossAmount()).isEqualByComparingTo(sumIncrease);
        assertThat(r.deductionAmount()).isEqualByComparingTo(sumDecrease);
        assertThat(r.netAmount()).isEqualByComparingTo(sumIncrease.subtract(sumDecrease));
    }

    @Test
    @DisplayName("工龄不足一年：工龄工资为 0")
    void seniorityLessThanOneYear() {
        PayrollResult r = calculator.calculate(
                ctx("8000", LocalDate.of(2026, 6, 1), null, Map.of(), Map.of()));

        assertThat(amountOf(r, PayrollCalculator.SENIORITY_PAY)).isEqualByComparingTo("0.00");
        assertThat(r.grossAmount()).isEqualByComparingTo("8000.00");
    }

    @Test
    @DisplayName("入职日期晚于期间末日：工龄为 0，不抛异常")
    void hireDateAfterPeriod() {
        assertThat(calculator.seniorityYears(
                LocalDate.of(2027, 1, 1), LocalDate.of(2026, 11, 30))).isZero();
    }

    private BigDecimal amountOf(PayrollResult r, String code) {
        return r.items().stream()
                .filter(i -> i.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("明细中缺少工资项: " + code))
                .amount();
    }
}
