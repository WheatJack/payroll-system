package com.insbean.payroll.domain.payroll;

import java.math.BigDecimal;

public record PayItemResult(
        String code,
        String name,
        PayCategory category,
        PayDirection direction,
        BigDecimal amount,
        PaySource source
) {
}
