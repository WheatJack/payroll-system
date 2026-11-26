package com.insbean.payroll.domain.payroll;

import java.math.BigDecimal;

/** 计算规则常量。改动这里的任何一个值都等同于修改业务规则，需业务确认。 */
public final class PayrollRules {

    /** 月平均计薪天数。任职不足整月时按此折算日工资（C12）。 */
    public static final BigDecimal MONTHLY_PAYABLE_DAYS = new BigDecimal("21.75");

    /** 工龄工资：每满一年 100 元（C13）。 */
    public static final BigDecimal SENIORITY_PAY_PER_YEAR = new BigDecimal("100");

    /** 明细落库的小数位。 */
    public static final int AMOUNT_SCALE = 2;

    /** 中间计算的小数位，只在落明细时舍入一次。 */
    public static final int CALC_SCALE = 10;

    private PayrollRules() {
    }
}
