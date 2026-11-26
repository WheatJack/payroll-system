package com.insbean.payroll.employee.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 契约 components.schemas.CompProfile：一段生效区间。effectiveTo 为 null 表示至今。 */
public record CompProfileDTO(
        Long id,
        Long employeeId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal baseSalary,
        String changeReason,
        Integer status,
        List<CompProfileItemDTO> items
) {
}
