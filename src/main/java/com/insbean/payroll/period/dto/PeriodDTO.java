package com.insbean.payroll.period.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 契约 components.schemas.Period。 */
public record PeriodDTO(
        Long id,
        Long companyId,
        String periodYm,
        String status,
        Integer employeeCount,
        Integer lastRunFailCount,
        BigDecimal totalGross,
        BigDecimal totalNet,
        BigDecimal hpd,
        Long compiledBy,
        Long lockedBy,
        LocalDateTime lockedAt,
        String unlockReason
) {
}
