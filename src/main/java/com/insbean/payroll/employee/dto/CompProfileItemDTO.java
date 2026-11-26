package com.insbean.payroll.employee.dto;

import java.math.BigDecimal;

/** 契约 components.schemas.CompProfileItem。 */
public record CompProfileItemDTO(
        String itemCode,
        BigDecimal amount
) {
}
