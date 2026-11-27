package com.insbean.payroll.tax.dto;

import java.math.BigDecimal;

/**
 * 年度个税核对行：与 pay_ytd 台账逐项对应，便于财务逐人核对。
 *
 * @param cumBasicDeduction 累计减除费用 = 5000 × paidMonths
 * @param taxable           应纳税所得额（不小于 0）
 * @param lastTax           最近一期个税
 * @param effectiveRate     实际税负率 = cumTax / cumIncome（4 位小数）
 */
public record AnnualTaxRow(
        Long employeeId,
        String empNo,
        String empName,
        String deptName,
        Integer paidMonths,
        BigDecimal cumIncome,
        BigDecimal cumBasicDeduction,
        BigDecimal cumSocial,
        BigDecimal cumFund,
        BigDecimal cumSpecial,
        BigDecimal taxable,
        BigDecimal cumTax,
        BigDecimal lastTax,
        BigDecimal effectiveRate
) {
}
