package com.insbean.payroll.domain.payroll;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合计金额恒等于明细加总，不做二次舍入（见设计文档 §4.4）。
 */
public record PayrollResult(
        List<PayItemResult> items,
        BigDecimal grossAmount,
        BigDecimal deductionAmount,
        BigDecimal netAmount
) {
}
