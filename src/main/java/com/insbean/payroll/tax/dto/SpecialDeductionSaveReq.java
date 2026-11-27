package com.insbean.payroll.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SpecialDeductionSaveReq(
        String deductionType,
        BigDecimal monthlyAmount,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String remark
) {
}
