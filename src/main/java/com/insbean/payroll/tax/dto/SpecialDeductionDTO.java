package com.insbean.payroll.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SpecialDeductionDTO(
        Long id,
        Long employeeId,
        String deductionType,
        BigDecimal monthlyAmount,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String remark
) {
}
