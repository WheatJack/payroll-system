package com.insbean.payroll.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InsuranceParamDTO(
        String cityCode,
        String cityName,
        String insuranceType,
        BigDecimal personalRate,
        BigDecimal baseMin,
        BigDecimal baseMax,
        BigDecimal extraAmount,
        LocalDate effectiveFrom
) {
}
