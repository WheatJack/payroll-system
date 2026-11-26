package com.insbean.payroll.period.dto;

import java.math.BigDecimal;

/** 契约 components.schemas.Period。 */
public record PeriodDTO(
        Long id,
        Long companyId,
        String periodYm,
        String status,
        Integer employeeCount,
        BigDecimal totalGross,
        BigDecimal totalNet,
        BigDecimal hpd
) {
}
