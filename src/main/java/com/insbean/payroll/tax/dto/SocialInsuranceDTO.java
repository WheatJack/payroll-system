package com.insbean.payroll.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SocialInsuranceDTO(
        Long id,
        Long employeeId,
        String cityCode,
        BigDecimal socialBase,
        BigDecimal housingFundRate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {
}
